/*
 * Copyright 2018 dc-square GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hivemq.extensions.rbac.configuration;

import com.hivemq.extensions.rbac.configuration.entities.ExtensionConfig;
import com.hivemq.extensions.rbac.configuration.entities.FileAuthConfig;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;


public class ConfigParserTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private ConfigParser configParser;

    @Before
    public void before() {
        configParser = new ConfigParser(new ExtensionConfig());
    }

    @Test
    public void test_valid_config() throws Exception {

        final URL resource = this.getClass().getClassLoader().getResource("credentials.xml");
        final File file = new File(resource.toURI());

        final FileAuthConfig fileAuthConfig = configParser.read(file);

        assertEquals(2, fileAuthConfig.getRoles().size());
        assertEquals(2, fileAuthConfig.getUsers().size());
    }

    @Test
    public void test_not_exising_file() throws Exception {

        final File file = new File(temporaryFolder.getRoot(), "not-existing.xml");

        final FileAuthConfig fileAuthConfig = configParser.read(file);

        assertNull(fileAuthConfig);
    }

    @Test
    public void test_invalid_file() throws Exception {

        final File file = new File(temporaryFolder.getRoot(), "invalid.xml");

        Files.writeString(file.toPath(), "<file-rbac></file-rbac>");

        final FileAuthConfig fileAuthConfig = configParser.read(file);

        assertNull(fileAuthConfig);
    }

    @Test
    public void tes_parsed_config_null() throws IOException {
        XmlParser mockParser = mock(XmlParser.class);
        File file = temporaryFolder.newFile("credentials.xml");

        when(mockParser.unmarshalFileAuthConfig(file)).thenReturn(null);

        ConfigParser parser = new ConfigParser(new ExtensionConfig(), mockParser);
        assertNull(parser.read(file));
    }



}