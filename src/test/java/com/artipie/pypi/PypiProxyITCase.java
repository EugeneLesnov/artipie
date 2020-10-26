/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 artipie.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.artipie.pypi;

import com.artipie.ArtipieServer;
import com.artipie.RepoConfigYaml;
import com.artipie.RepoPerms;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.fs.FileStorage;
import com.artipie.asto.test.TestResource;
import com.jcabi.log.Logger;
import java.io.IOException;
import java.nio.file.Path;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;

/**
 * Test to pypi proxy.
 * @since 0.12
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@EnabledOnOs({OS.LINUX, OS.MAC})
public final class PypiProxyITCase {

    /**
     * Host.
     */
    private static final String HOST = "host.testcontainers.internal";

    /**
     * Temporary directory for all tests.
     * @checkstyle VisibilityModifierCheck (3 lines)
     */
    @TempDir
    Path tmp;

    /**
     * Test origin.
     */
    private ArtipieServer origin;

    /**
     * Storage.
     */
    private Storage storage;

    /**
     * Test proxy.
     */
    private ArtipieServer proxy;

    /**
     * Container.
     */
    private GenericContainer<?> cntn;

    /**
     * Artipie proxy server port.
     */
    private int port;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void installFromProxy(final boolean anonymous) throws Exception {
        this.init(anonymous);
        new TestResource("pypi-repo/alarmtime-0.1.5.tar.gz").saveTo(
            this.storage,
            new Key.From("origin", "my-pypi", "alarmtime", "alarmtime-0.1.5.tar.gz")
        );
        MatcherAssert.assertThat(
            this.exec(
                "pip", "install", "--no-deps", "--trusted-host", PypiProxyITCase.HOST,
                "--index-url", this.url(anonymous), "alarmtime"
            ),
            Matchers.containsString("Successfully installed alarmtime-0.1.5")
        );
    }

    @AfterEach
    void stop() {
        this.proxy.stop();
        this.origin.stop();
        this.cntn.stop();
    }

    private void init(final boolean anonymous) throws IOException {
        this.storage = new FileStorage(this.tmp);
        this.origin = new ArtipieServer(
            this.tmp, "my-pypi", this.originConfig(anonymous)
        );
        this.proxy = new ArtipieServer(
            this.tmp, "my-pypi-proxy", this.proxyConfig(anonymous, this.origin.start())
        );
        this.port = this.proxy.start();
        Testcontainers.exposeHostPorts(this.port);
        this.cntn = new GenericContainer<>("python:3")
            .withCommand("tail", "-f", "/dev/null")
            .withWorkingDirectory("/home/")
            .withFileSystemBind(this.tmp.toString(), "/home");
        this.cntn.start();
    }

    private RepoConfigYaml originConfig(final boolean anonymous) {
        final RepoConfigYaml yaml = new RepoConfigYaml("pypi")
            .withFileStorage(this.tmp.resolve("origin"));
        if (!anonymous) {
            yaml.withPermissions(
                new RepoPerms(ArtipieServer.ALICE.name(), "*")
            );
        }
        return yaml;
    }

    private RepoConfigYaml proxyConfig(final boolean anonymous, final int prt) {
        final RepoConfigYaml yaml = new RepoConfigYaml("pypi-proxy")
            .withFileStorage(this.tmp.resolve("proxy"));
        final String url = String.format("http://localhost:%d", prt);
        if (anonymous) {
            yaml.withRemote(url);
        } else {
            yaml.withRemote(
                url,
                ArtipieServer.ALICE.name(),
                ArtipieServer.ALICE.password()
            );
        }
        return yaml;
    }

    private String exec(final String... command) throws Exception {
        Logger.debug(this, "Command:\n%s", String.join(" ", command));
        return this.cntn.execInContainer(command).getStdout();
    }

    private String url(final boolean anonymous) {
        final String urlcntn;
        if (anonymous) {
            urlcntn = String.format("http://%s:%d/my-pypi/", PypiProxyITCase.HOST, this.port);
        } else {
            urlcntn = String.format(
                "http://%s:%s@%s:%d/my-pypi/",
                ArtipieServer.ALICE.name(), ArtipieServer.ALICE.password(),
                PypiProxyITCase.HOST, this.port
            );
        }
        return urlcntn;
    }
}