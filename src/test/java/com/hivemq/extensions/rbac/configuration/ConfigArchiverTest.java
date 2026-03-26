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

import com.hivemq.extensions.rbac.configuration.entities.FileAuthConfig;
import com.hivemq.extensions.rbac.configuration.entities.Permission;
import com.hivemq.extensions.rbac.configuration.entities.Role;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.File;
import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.*;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.whenNew;

@RunWith(PowerMockRunner.class)
@PrepareForTest(ConfigArchiver.class)
public class ConfigArchiverTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File folder;
    private ConfigArchiver configArchiver;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        folder = temporaryFolder.newFolder();

        configArchiver = new ConfigArchiver(folder, new XmlParser());
    }

    private FileAuthConfig validConfig() {
        FileAuthConfig config = new FileAuthConfig();
        config.setRoles(List.of(new Role("id1", List.of(new Permission("topic1")))));
        return config;
    }


    private Date fixedDateAtHour(int hour) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, 30);
        calendar.set(Calendar.SECOND, 45);
        return calendar.getTime();
    }


    @Test
    public void test_archive() throws Exception {

        final FileAuthConfig config = new FileAuthConfig();

        config.setRoles(List.of(new Role("id1", List.of(new Permission("topic1")))));

        configArchiver.archive(config);

        final File[] files = folder.listFiles();
        assertEquals(1, files.length);

        final File archiveFolder = files[0];
        assertEquals("credentials-archive", archiveFolder.getName());
        assertEquals(true, archiveFolder.isDirectory());

        assertEquals(1, archiveFolder.listFiles().length);
        System.out.println(archiveFolder.listFiles()[0].getName());
        assertTrue(archiveFolder.listFiles()[0].getName().startsWith("20"));
        assertTrue(archiveFolder.listFiles()[0].getName().endsWith("credentials.xml"));

    }

    @Test
    public void test_when_archive_folder_is_file_deletable_should_delete() throws IOException {
        // Use the same 'folder' from @Before — NOT a new separate folder
        File credentialsArchiveAsFile = new File(folder, "credentials-archive");
        assertTrue("Test setup failed — could not create file",
                credentialsArchiveAsFile.createNewFile());
        assertTrue("File should exist before test",
                credentialsArchiveAsFile.isFile());

        FileAuthConfig config = new FileAuthConfig();

        try {
            configArchiver.archive(config);
        } catch (IOException e) {
            fail("Should not throw IOException when file is successfully deleted, but got: "
                    + e.getMessage());
        }
    }

    @Test
    public void test_archiving_2pm_filename_ShouldContain14Not02() throws Exception {
        // Force new Date() inside ConfigArchiver to return 14:30:45
        whenNew(Date.class).withNoArguments().thenReturn(fixedDateAtHour(14));

        configArchiver.archive(validConfig());

        File archiveFolder = new File(folder, "credentials-archive");
        String filename = archiveFolder.listFiles()[0].getName();
        System.out.println("Generated filename: " + filename);

        assertTrue(
                "Filename should contain '14' for 2PM, but was: " + filename,
                filename.contains("-14-")
        );
        assertFalse(
                "Filename should NOT contain '02' (12-hour) for 2PM, but was: " + filename,
                filename.contains("-02-")
        );
    }
}