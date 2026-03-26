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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static com.hivemq.extensions.rbac.configuration.ExtensionConfiguration.EXTENSION_CONFIG_FILE_NAME;
import static com.hivemq.extensions.rbac.configuration.entities.PasswordType.HASHED;
import static org.junit.Assert.*;


public class ExtensionConfigurationTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void test_read_extension_configuration() throws Exception {

        final File configFile = new File(temporaryFolder.getRoot(), EXTENSION_CONFIG_FILE_NAME);

        Files.writeString(configFile.toPath(),
                "<extension-configuration>" +
                "   <credentials-reload-interval>999</credentials-reload-interval>" +
                "   <password-type>HASHED</password-type>" +
                "   <listener-names>" +
                "       <listener-name>normal-listener</listener-name>" +
                "   </listener-names>" +
                "</extension-configuration>");

        final ExtensionConfiguration extensionConfiguration = new ExtensionConfiguration(temporaryFolder.getRoot());

        final ExtensionConfig extensionConfig = extensionConfiguration.getExtensionConfig();

        assertNotNull(extensionConfig);
        assertEquals(999, extensionConfig.getReloadInterval());
        assertEquals(HASHED, extensionConfig.getPasswordType());
        assertEquals(1, extensionConfig.getListenerNames().size());
        assertTrue(extensionConfig.getListenerNames().contains("normal-listener"));
    }


}