/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.storage.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tech.pegasys.teku.util.config.StateStorageMode.PRUNE;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.pegasys.teku.metrics.StubMetricsSystem;
import tech.pegasys.teku.util.config.Eth1Address;
import tech.pegasys.teku.util.config.StateStorageMode;

public class VersionedDatabaseFactoryTest {

  private static final StateStorageMode DATA_STORAGE_MODE = PRUNE;
  private final Eth1Address eth1Address =
      Eth1Address.fromHexString("0x77f7bED277449F51505a4C54550B074030d989bC");
  @TempDir Path dataDir;

  @Test
  public void createDatabase_fromEmptyDataDir() throws Exception {
    final DatabaseFactory dbFactory =
        new VersionedDatabaseFactory(
            new StubMetricsSystem(), dataDir.toString(), DATA_STORAGE_MODE);
    try (final Database db = dbFactory.createDatabase()) {
      assertThat(db).isNotNull();

      assertDbVersionSaved(dataDir, DatabaseVersion.DEFAULT_VERSION);
    }
  }

  @Test
  public void createDatabase_fromExistingDataDir() throws Exception {
    createDbDirectory(dataDir);
    createVersionFile(dataDir, DatabaseVersion.V3);

    final DatabaseFactory dbFactory =
        new VersionedDatabaseFactory(
            new StubMetricsSystem(), dataDir.toString(), DATA_STORAGE_MODE);
    try (final Database db = dbFactory.createDatabase()) {
      assertThat(db).isNotNull();
    }
    assertThat(((VersionedDatabaseFactory) dbFactory).getDatabaseVersion())
        .isEqualTo(DatabaseVersion.V3);
  }

  @Test
  public void createDatabase_asV4Database() throws Exception {
    final DatabaseFactory dbFactory =
        new VersionedDatabaseFactory(
            new StubMetricsSystem(), dataDir.toString(), DATA_STORAGE_MODE, "4", 1L, eth1Address);
    try (final Database db = dbFactory.createDatabase()) {
      assertThat(db).isNotNull();
      assertDbVersionSaved(dataDir, DatabaseVersion.V4);
    }
    final File dbDirectory = new File(dataDir.toFile(), VersionedDatabaseFactory.DB_PATH);
    final File archiveDirectory = new File(dataDir.toFile(), VersionedDatabaseFactory.ARCHIVE_PATH);
    assertThat(dbDirectory).exists();
    assertThat(archiveDirectory).exists();
  }

  @Test
  public void createDatabase_asV5Database() throws Exception {
    final DatabaseFactory dbFactory =
        new VersionedDatabaseFactory(
            new StubMetricsSystem(), dataDir.toString(), DATA_STORAGE_MODE, "5", 1L, eth1Address);
    try (final Database db = dbFactory.createDatabase()) {
      assertThat(db).isNotNull();
      assertDbVersionSaved(dataDir, DatabaseVersion.V5);
    }
    final File dbDirectory = new File(dataDir.toFile(), VersionedDatabaseFactory.DB_PATH);
    final File archiveDirectory = new File(dataDir.toFile(), VersionedDatabaseFactory.ARCHIVE_PATH);
    final File metadataFile =
        new File(dataDir.toFile(), VersionedDatabaseFactory.METADATA_FILENAME);
    assertThat(dbDirectory).exists();
    assertThat(archiveDirectory).exists();
    assertThat(metadataFile).exists();
  }

  @Test
  public void createDatabase_invalidVersionFile() throws Exception {
    createDbDirectory(dataDir);
    createVersionFile(dataDir, "bla");

    final DatabaseFactory dbFactory =
        new VersionedDatabaseFactory(
            new StubMetricsSystem(), dataDir.toString(), DATA_STORAGE_MODE);
    assertThatThrownBy(dbFactory::createDatabase)
        .isInstanceOf(DatabaseStorageException.class)
        .hasMessageContaining("Unrecognized database version: bla");
  }

  @Test
  public void createDatabase_dbExistsButNoVersionIsSaved() throws Exception {
    createDbDirectory(dataDir);

    final DatabaseFactory dbFactory =
        new VersionedDatabaseFactory(
            new StubMetricsSystem(), dataDir.toString(), DATA_STORAGE_MODE);
    assertThatThrownBy(dbFactory::createDatabase)
        .isInstanceOf(DatabaseStorageException.class)
        .hasMessageContaining("No database version file was found");
  }

  @Test
  public void createDatabase_shouldAllowV4Database() {
    createDbDirectory(dataDir);
    final VersionedDatabaseFactory dbFactory =
        new VersionedDatabaseFactory(
            new StubMetricsSystem(), dataDir.toString(), DATA_STORAGE_MODE, "4", 1L, eth1Address);
    assertThat(dbFactory.getDatabaseVersion()).isEqualTo(DatabaseVersion.V4);
  }

  @Test
  public void createDatabase_shouldAllowV3Database() {
    createDbDirectory(dataDir);
    final VersionedDatabaseFactory dbFactory =
        new VersionedDatabaseFactory(
            new StubMetricsSystem(), dataDir.toString(), DATA_STORAGE_MODE, "3.0", 1L, eth1Address);
    assertThat(dbFactory.getDatabaseVersion()).isEqualTo(DatabaseVersion.V3);
  }

  private void createDbDirectory(final Path dataPath) {
    final File dbDirectory =
        Paths.get(dataPath.toAbsolutePath().toString(), VersionedDatabaseFactory.DB_PATH).toFile();
    dbDirectory.mkdirs();
    assertThat(dbDirectory).isEmptyDirectory();
  }

  private void createVersionFile(final Path dataPath, final DatabaseVersion version)
      throws Exception {
    createVersionFile(dataPath, version.getValue());
    assertDbVersionSaved(dataPath, version);
  }

  private void createVersionFile(final Path dataPath, final String version) throws IOException {
    Path versionPath = dataPath.resolve(VersionedDatabaseFactory.DB_VERSION_PATH);
    Files.writeString(versionPath, version);
  }

  private void assertDbVersionSaved(final Path dataDirectory, final DatabaseVersion defaultVersion)
      throws IOException {
    final String versionValue =
        Files.readString(dataDirectory.resolve(VersionedDatabaseFactory.DB_VERSION_PATH));
    assertThat(versionValue).isEqualTo(defaultVersion.getValue());
  }
}
