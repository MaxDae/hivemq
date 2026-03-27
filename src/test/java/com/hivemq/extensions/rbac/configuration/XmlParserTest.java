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
import com.hivemq.extensions.rbac.configuration.entities.PasswordType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import java.io.File;
import java.io.IOException;
import java.net.URL;

import static org.junit.Assert.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;

public class XmlParserTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test(expected = NotMarshallableException.class)
    public void test_marshal_directory() throws Exception {

        final XmlParser xmlParser = new XmlParser();
        final File file = temporaryFolder.newFolder();

        xmlParser.marshal(new FileAuthConfig(), file);
    }

    @Test(expected = NotMarshallableException.class)
    public void test_marshal_file_exists() throws Exception {

        final XmlParser xmlParser = new XmlParser();
        final File file = temporaryFolder.newFile();

        xmlParser.marshal(new FileAuthConfig(), file);
    }

    @Test
    public void test_marshal_unmarshal_credentials() throws Exception {

        final XmlParser xmlParser = new XmlParser();

        final URL resource = this.getClass().getClassLoader().getResource("credentials.xml");
        final File file = new File(resource.toURI());

        final FileAuthConfig config = xmlParser.unmarshalFileAuthConfig(file);

        final File testFile = new File(temporaryFolder.getRoot(), "test.xml");
        xmlParser.marshal(config, testFile);

        final FileAuthConfig marshalledConfig = xmlParser.unmarshalFileAuthConfig(testFile);

        assertEquals(config.toString().trim(), marshalledConfig.toString().trim());
    }

    @Test
    public void test_unmarshal_extension_config() throws Exception {

        final XmlParser xmlParser = new XmlParser();

        final URL resource = this.getClass().getClassLoader().getResource("test-extension-config.xml");
        final File file = new File(resource.toURI());

        final ExtensionConfig config = xmlParser.unmarshalExtensionConfig(file);

        assertEquals(PasswordType.HASHED, config.getPasswordType());
        assertEquals(120, config.getReloadInterval());
    }

    @Test
    public void test_marshal_non_writable_directory_throws_before_jaxb()
            throws Exception {

        JAXBContext mockJaxb = mock(JAXBContext.class);
        Marshaller mockMarshaller = mock(Marshaller.class);
        when(mockJaxb.createMarshaller()).thenReturn(mockMarshaller);

        XmlParser xmlParser = new XmlParser(mockJaxb); // ← injectable

        File folder = temporaryFolder.newFolder("non-writable");
        folder.setWritable(false);
        File file = new File(folder, "credentials.xml");

        try {
            xmlParser.marshal(new FileAuthConfig(), file);
            fail("Should throw NotMarshallableException");

        } catch (NotMarshallableException e) {
            verify(mockJaxb, never()).createMarshaller();

        } finally {
            folder.setWritable(true);
        }
    }

    @Test
    public void test_file_not_exists_trow_io_exception() {
        File nonExistent = new File(temporaryFolder.getRoot(),
                "does-not-exist.xml");
        assertFalse("Setup: file should not exist", nonExistent.exists());
        final XmlParser xmlParser = new XmlParser();

        try {
            xmlParser.unmarshalFileAuthConfig(nonExistent);
            fail("Should throw IOException for non-existent file");

        } catch (IOException e) {
            assertTrue(
                    "Error should mention file path",
                    e.getMessage().contains(nonExistent.getAbsolutePath())
            );
        }
    }

    @Test
    public void test_file_not_readable_throws_io_exception() throws Exception {
        File unreadableFile = temporaryFolder.newFile("unreadable.xml");
        assertTrue("Setup: file should exist", unreadableFile.exists());
        assertTrue("Setup: make file unreadable",
                unreadableFile.setReadable(false));

        final XmlParser xmlParser = new XmlParser();
        try {
            xmlParser.unmarshalFileAuthConfig(unreadableFile);
            fail("Should throw IOException for unreadable file");

        } catch (IOException e) {
            assertTrue(
                    "Error should mention file path",
                    e.getMessage().contains(unreadableFile.getAbsolutePath())
            );
        } finally {
            unreadableFile.setReadable(true);
        }
    }

    @Test
    public void test_file_is_directory_throws_io_exception() throws Exception {
        File directory = temporaryFolder.newFolder("i-am-a-directory");
        assertTrue("Setup: should be a directory", directory.isDirectory());
        final XmlParser xmlParser = new XmlParser();

        try {
            xmlParser.unmarshalFileAuthConfig(directory);
            fail("Should throw IOException when file is a directory");

        } catch (IOException e) {
            assertTrue(
                    "Error should mention file path",
                    e.getMessage().contains(directory.getAbsolutePath())
            );
        }
    }
}