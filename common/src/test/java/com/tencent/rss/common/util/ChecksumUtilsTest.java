/*
 * Tencent is pleased to support the open source community by making
 * Firestorm-Spark remote shuffle server available. 
 *
 * Copyright (C) 2021 THL A29 Limited, a Tencent company.  All rights reserved. 
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.rss.common.util;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import java.util.zip.CRC32;
import org.junit.Test;

public class ChecksumUtilsTest {

  @Test
  public void crc32TestWithByte() {
    byte[] data = new byte[32 * 1024 * 1024];
    new Random().nextBytes(data);
    CRC32 crc32 = new CRC32();
    crc32.update(data);
    long expected = crc32.getValue();
    assertEquals(expected, ChecksumUtils.getCrc32(data));

    data = new byte[32 * 1024];
    new Random().nextBytes(data);
    crc32 = new CRC32();
    crc32.update(data);
    expected = crc32.getValue();
    assertEquals(expected, ChecksumUtils.getCrc32(data));
  }

  @Test
  public void crc32TestWithByteBuff() throws Exception {
    int length = 32 * 1024 * 1024;
    byte[] data = new byte[length];
    new Random().nextBytes(data);

    String tempDir = Files.createTempDirectory("rss").toString();
    File file = new File(tempDir, "crc_test.txt");
    file.createNewFile();
    file.deleteOnExit();

    try (FileOutputStream outputStream = new FileOutputStream(file)) {
      outputStream.write(data);
    }

    long expectedChecksum = ChecksumUtils.getCrc32(data);

    // test direct ByteBuffer
    Path path = Paths.get(file.getAbsolutePath());
    FileChannel fileChannel = FileChannel.open(path);
    ByteBuffer buffer = ByteBuffer.allocateDirect(length);
    int bytesRead = fileChannel.read(buffer);
    fileChannel.close();
    assertEquals(length, bytesRead);
    buffer.flip();
    assertEquals(expectedChecksum, ChecksumUtils.getCrc32(buffer));
    assertEquals(length, buffer.position());

    // test heap ByteBuffer
    path = Paths.get(file.getAbsolutePath());
    fileChannel = FileChannel.open(path);
    buffer = ByteBuffer.allocate(length);
    bytesRead = fileChannel.read(buffer);
    fileChannel.close();
    assertEquals(length, bytesRead);
    buffer.flip();
    assertEquals(expectedChecksum, ChecksumUtils.getCrc32(buffer));

  }
}
