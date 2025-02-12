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

package com.tencent.rss.server;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Uninterruptibles;
import com.tencent.rss.storage.common.DiskItem;
import com.tencent.rss.storage.common.ShuffleFileInfo;
import com.tencent.rss.storage.factory.ShuffleUploadHandlerFactory;
import com.tencent.rss.storage.handler.api.ShuffleUploadHandler;
import com.tencent.rss.storage.util.ShuffleUploadResult;
import com.tencent.rss.storage.util.StorageType;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.hadoop.conf.Configuration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.roaringbitmap.RoaringBitmap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


public class ShuffleUploaderTest  {

  @ClassRule
  public static final TemporaryFolder tmpDir = new TemporaryFolder();
  private static File base;

  @BeforeClass
  public static void setUp() throws IOException {
    base = tmpDir.newFolder("ShuffleUploaderTest");
    ShuffleServerMetrics.register();
  }

  @AfterClass
  public static void tearDown() {
    tmpDir.delete();
  }


  @Test
  public void builderTest() {
    DiskItem mockDiskItem = mock(DiskItem.class);
    when(mockDiskItem.getBasePath()).thenReturn("test/base");
    assertException(
        IllegalArgumentException.class,
        (Void) -> new ShuffleUploader.Builder().diskItem(mockDiskItem).build());

    assertException(
        IllegalArgumentException.class,
        (Void) -> new ShuffleUploader.Builder().diskItem(mockDiskItem).uploadThreadNum(2).build());

    assertException(
        IllegalArgumentException.class,
        (Void) -> new ShuffleUploader.Builder().diskItem(mockDiskItem).uploadThreadNum(2).uploadIntervalMS(3).build());

    assertException(
        IllegalArgumentException.class,
        (Void) -> new ShuffleUploader.Builder()
            .diskItem(mockDiskItem).uploadThreadNum(2).uploadIntervalMS(3).uploadCombineThresholdMB(300).build());

    assertException(
        IllegalArgumentException.class,
        (Void) -> new ShuffleUploader.Builder()
            .diskItem(mockDiskItem)
            .uploadThreadNum(2)
            .uploadIntervalMS(3)
            .uploadCombineThresholdMB(300)
            .referenceUploadSpeedMBS(1)
            .build());

    assertException(
        IllegalArgumentException.class,
        (Void) -> new ShuffleUploader.Builder()
            .diskItem(mockDiskItem)
            .uploadThreadNum(2)
            .uploadIntervalMS(3)
            .uploadCombineThresholdMB(300)
            .referenceUploadSpeedMBS(1)
            .remoteStorageType(null)
            .build());

    assertException(
        IllegalArgumentException.class,
        (Void) -> new ShuffleUploader.Builder()
            .diskItem(mockDiskItem)
            .uploadThreadNum(2)
            .uploadIntervalMS(3)
            .uploadCombineThresholdMB(300)
            .referenceUploadSpeedMBS(1)
            .hdfsBathPath("hdfs://base")
            .serverId("")
            .hadoopConf(new Configuration())
            .build());

    assertException(
        IllegalArgumentException.class,
        (Void) -> new ShuffleUploader.Builder()
            .diskItem(mockDiskItem)
            .uploadThreadNum(2)
            .uploadIntervalMS(3)
            .uploadCombineThresholdMB(300)
            .hdfsBathPath("hdfs://")
            .serverId("prefix")
            .hadoopConf(new Configuration())
            .maxShuffleSize(0)
            .build());

    new ShuffleUploader.Builder()
        .diskItem(mockDiskItem)
        .uploadThreadNum(2)
        .uploadIntervalMS(3)
        .uploadCombineThresholdMB(300)
        .referenceUploadSpeedMBS(1)
        .remoteStorageType(StorageType.HDFS)
        .hdfsBathPath("hdfs://base")
        .serverId("prefix")
        .hadoopConf(new Configuration())
        .build();
  }

  @Test
  public void selectShuffleFiles() {
    try {
      String app1 = "app-1";
      String shuffle1 = "1";
      String shuffleKey1 = String.join("/", app1, shuffle1);
      File partitionDir1 = tmpDir.newFolder(base.getName(), app1, shuffle1, "1-1");
      File partitionDir2 = tmpDir.newFolder(base.getName(), app1, shuffle1, "2-2");
      File partitionDir3 = tmpDir.newFolder(base.getName(), app1, shuffle1, "3-3");
      File partitionDir4 = tmpDir.newFolder(base.getName(), app1, shuffle1, "4-4");
      File partitionDir5 = tmpDir.newFolder(base.getName(), app1, shuffle1, "5-5");

      File dataFile1 = new File(partitionDir1.getAbsolutePath() + "/127.0.0.1-8080.data");
      File dataFile2 = new File(partitionDir2.getAbsolutePath() + "/127.0.0.1-8080.data");
      File dataFile3 = new File(partitionDir3.getAbsolutePath() + "/127.0.0.1-8080.data");
      File dataFile4 = new File(partitionDir5.getAbsolutePath() + "/127.0.0.1-8080.data");

      File indexFile1 = new File(partitionDir1.getAbsolutePath() + "/127.0.0.1-8080.index");
      File indexFile2 = new File(partitionDir2.getAbsolutePath() + "/127.0.0.1-8080.index");
      File indexFile3 = new File(partitionDir3.getAbsolutePath() + "/127.0.0.1-8080.index");
      File indexFile5 = new File(partitionDir4.getAbsolutePath() + "/127.0.0.1-8080.index");

      List<File> dataFiles = Lists.newArrayList(dataFile1, dataFile2, dataFile3, dataFile4);
      dataFiles.forEach(f -> writeFile(f, 10));

      List<File> indexFiles = Lists.newArrayList(indexFile1, indexFile2, indexFile3, indexFile5);
      indexFiles.forEach(f -> writeFile(f, 10));

      DiskItem mockDiskItem = mock(DiskItem.class);
      when(mockDiskItem.getBasePath()).thenReturn(base.getAbsolutePath());
      ShuffleUploader shuffleUploader = new ShuffleUploader.Builder()
          .diskItem(mockDiskItem)
          .uploadThreadNum(2)
          .uploadIntervalMS(3)
          .uploadCombineThresholdMB(300)
          .referenceUploadSpeedMBS(1)
          .remoteStorageType(StorageType.HDFS)
          .hdfsBathPath("hdfs://base")
          .serverId("127.0.0.1-8080")
          .hadoopConf(new Configuration())
          .build();

      when(mockDiskItem.getSortedShuffleKeys(true, 4))
          .thenReturn(Lists.newArrayList(shuffleKey1, "zeroPartitionShuffleKey", "zeroSizeShuffleKey"));
      when(mockDiskItem.getSortedShuffleKeys(true, 1))
          .thenReturn(Lists.newArrayList(shuffleKey1));
      when(mockDiskItem.getNotUploadedSize("zeroSizeShuffleKey"))
          .thenReturn(10L);
      when(mockDiskItem.getNotUploadedPartitions("zeroSizeShuffleKey"))
          .thenReturn(RoaringBitmap.bitmapOf());
      when(mockDiskItem.getNotUploadedSize("zeroSizeShuffleKey"))
          .thenReturn(0L);
      when(mockDiskItem.getNotUploadedPartitions("zeroSizeShuffleKey"))
          .thenReturn(RoaringBitmap.bitmapOf(1));
      when(mockDiskItem.getNotUploadedSize(shuffleKey1))
          .thenReturn(30L);
      when(mockDiskItem.getNotUploadedPartitions(shuffleKey1))
          .thenReturn(RoaringBitmap.bitmapOf(1, 2, 3));
      when(mockDiskItem.getNotUploadedPartitions("zeroPartitionShuffleKey"))
          .thenReturn(RoaringBitmap.bitmapOf());
      when(mockDiskItem.getHighWaterMarkOfWrite()).thenReturn(100.0);
      when(mockDiskItem.getLowWaterMarkOfWrite()).thenReturn(0.0);
      when(mockDiskItem.getCapacity()).thenReturn(1024L);

      List<ShuffleFileInfo> shuffleFileInfos = shuffleUploader.selectShuffleFiles(4, false);
      assertEquals(1, shuffleFileInfos.size());
      ShuffleFileInfo shuffleFileInfo = shuffleFileInfos.get(0);
      assertResult3(dataFiles, indexFiles, shuffleFileInfo);
      shuffleUploader = new ShuffleUploader.Builder()
          .diskItem(mockDiskItem)
          .uploadThreadNum(2)
          .uploadIntervalMS(3)
          .uploadCombineThresholdMB(300)
          .referenceUploadSpeedMBS(1)
          .remoteStorageType(StorageType.HDFS)
          .hdfsBathPath("hdfs://base")
          .serverId("127.0.0.1-8080")
          .maxShuffleSize(5)
          .hadoopConf(new Configuration())
          .build();
      shuffleFileInfos = shuffleUploader.selectShuffleFiles(4, false);
      assertEquals(3, shuffleFileInfos.size());
      assertResult1(dataFiles, indexFiles, shuffleFileInfos);
      shuffleFileInfos = shuffleUploader.selectShuffleFiles(1, false);
      assertEquals(3, shuffleFileInfos.size());
      assertResult1(dataFiles, indexFiles, shuffleFileInfos);
      shuffleUploader = new ShuffleUploader.Builder()
          .diskItem(mockDiskItem)
          .uploadThreadNum(2)
          .uploadIntervalMS(3)
          .uploadCombineThresholdMB(300)
          .referenceUploadSpeedMBS(1)
          .remoteStorageType(StorageType.HDFS)
          .hdfsBathPath("hdfs://base")
          .serverId("127.0.0.1-8080")
          .maxShuffleSize(20)
          .hadoopConf(new Configuration())
          .build();
      shuffleFileInfos = shuffleUploader.selectShuffleFiles(4, false);
      assertEquals(1, shuffleFileInfos.size());
      shuffleFileInfo = shuffleFileInfos.get(0);
      assertResult3(dataFiles, indexFiles, shuffleFileInfo);
      shuffleUploader = new ShuffleUploader.Builder()
          .diskItem(mockDiskItem)
          .uploadThreadNum(2)
          .uploadIntervalMS(3)
          .uploadCombineThresholdMB(300)
          .referenceUploadSpeedMBS(1)
          .remoteStorageType(StorageType.HDFS)
          .hdfsBathPath("hdfs://base")
          .serverId("127.0.0.1-8080")
          .maxShuffleSize(15)
          .hadoopConf(new Configuration())
          .build();
      shuffleFileInfos = shuffleUploader.selectShuffleFiles(4, false);
      assertResult2(dataFiles, indexFiles, shuffleFileInfos);
      shuffleUploader = new ShuffleUploader.Builder()
          .diskItem(mockDiskItem)
          .uploadThreadNum(2)
          .uploadIntervalMS(3)
          .uploadCombineThresholdMB(300)
          .referenceUploadSpeedMBS(1)
          .remoteStorageType(StorageType.HDFS)
          .hdfsBathPath("hdfs://base")
          .serverId("127.0.0.1-8080")
          .maxShuffleSize(12)
          .hadoopConf(new Configuration())
          .build();
      shuffleFileInfos = shuffleUploader.selectShuffleFiles(4, false);
      assertResult2(dataFiles, indexFiles, shuffleFileInfos);

      when(mockDiskItem.getSortedShuffleKeys(false, 4))
          .thenReturn(Lists.newArrayList(shuffleKey1, "zeroPartitionShuffleKey", "zeroSizeShuffleKey"));
      when(mockDiskItem.getSortedShuffleKeys(false, 1))
          .thenReturn(Lists.newArrayList(shuffleKey1));
      shuffleUploader = new ShuffleUploader.Builder()
          .diskItem(mockDiskItem)
          .uploadThreadNum(2)
          .uploadIntervalMS(3)
          .uploadCombineThresholdMB(300)
          .referenceUploadSpeedMBS(1)
          .remoteStorageType(StorageType.HDFS)
          .hdfsBathPath("hdfs://base")
          .serverId("127.0.0.1-8080")
          .maxShuffleSize(9)
          .hadoopConf(new Configuration())
          .build();
      shuffleFileInfos = shuffleUploader.selectShuffleFiles(4, true);
      assertEquals(2, shuffleFileInfos.size());
      assertEquals(2, shuffleFileInfos.size());
      assertEquals(10, shuffleFileInfos.get(0).getSize());
      assertEquals(10, shuffleFileInfos.get(1).getSize());

      when(mockDiskItem.getHighWaterMarkOfWrite()).thenReturn(95.0);
      when(mockDiskItem.getLowWaterMarkOfWrite()).thenReturn(0.0);
      when(mockDiskItem.getCapacity()).thenReturn(11L);
      shuffleUploader = new ShuffleUploader.Builder()
          .diskItem(mockDiskItem)
          .uploadThreadNum(1)
          .uploadIntervalMS(3)
          .uploadCombineThresholdMB(300)
          .referenceUploadSpeedMBS(1)
          .remoteStorageType(StorageType.HDFS)
          .hdfsBathPath("hdfs://base")
          .serverId("127.0.0.1-8080")
          .maxShuffleSize(9)
          .hadoopConf(new Configuration())
          .build();
      shuffleFileInfos = shuffleUploader.selectShuffleFiles(4, true);
      assertEquals(1, shuffleFileInfos.size());
      assertResult4(dataFiles, indexFiles, shuffleFileInfos);
    } catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }

  @Test
  public void selectShuffleFilesRestrictionTest() {
    try {
      final int appNum = 4;
      final int partitionNum = 5;
      final List<String> shuffleKeys = Lists.newArrayList();
      for (int i = 0; i < appNum; ++i) {
        String appId = "app-" + i;
        String shuffleId = "1";
        String shuffleKey = String.join("/", appId, shuffleId);
        shuffleKeys.add(shuffleKey);

        for (int j = 0; j < partitionNum; ++j) {
          File partitionDir = tmpDir.newFolder(base.getName(), appId, shuffleId, j + "-" + j);
          File dataFile = new File(partitionDir.getAbsolutePath() + "/127.0.0.1-8080.data");
          File indexFile = new File(partitionDir.getAbsolutePath() + "/127.0.0.1-8080.index");
          writeFile(dataFile, 5 * (i + 1));
          writeFile(indexFile, 5);
        }
      }

      DiskItem mockDiskItem = mock(DiskItem.class);
      when(mockDiskItem.getBasePath()).thenReturn(base.getAbsolutePath());
      when(mockDiskItem.getSortedShuffleKeys(true, 4))
          .thenReturn(shuffleKeys);
      when(mockDiskItem.getNotUploadedSize(any()))
          .thenReturn(partitionNum * 10L);
      when(mockDiskItem.getNotUploadedPartitions(any()))
          .thenReturn(RoaringBitmap.bitmapOf(0, 1, 2, 3, 4));
      when(mockDiskItem.getHighWaterMarkOfWrite()).thenReturn(100.0);
      when(mockDiskItem.getLowWaterMarkOfWrite()).thenReturn(0.0);
      when(mockDiskItem.getCapacity()).thenReturn(1024L);
      when(mockDiskItem.getSortedShuffleKeys(false, 4))
          .thenReturn(shuffleKeys);
      ShuffleUploader shuffleUploader = new ShuffleUploader.Builder()
          .diskItem(mockDiskItem)
          .uploadThreadNum(4)
          .uploadIntervalMS(3)
          .uploadCombineThresholdMB(300)
          .referenceUploadSpeedMBS(10)
          .maxShuffleSize(10)
          .remoteStorageType(StorageType.HDFS)
          .hdfsBathPath("hdfs://base")
          .serverId("127.0.0.1-8080")
          .hadoopConf(new Configuration())
          .build();

      List<ShuffleFileInfo> shuffleFileInfos = shuffleUploader.selectShuffleFiles(4, false);
      assertEquals(15, shuffleFileInfos.size());
      // huge shuffle's segment num limited to thread num
      shuffleFileInfos = shuffleUploader.selectShuffleFiles(4, true);
      assertEquals(4, shuffleFileInfos.size());

    } catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }

  private void assertResult1(List<File> dataFiles, List<File> indexFiles, List<ShuffleFileInfo> shuffleFileInfos) {
    for (int i = 0; i < 3; ++i) {
      assertEquals(dataFiles.get(i).getAbsolutePath(), shuffleFileInfos.get(i).getDataFiles().get(0).getAbsolutePath());
      assertEquals(indexFiles.get(i).getAbsolutePath(), shuffleFileInfos.get(i).getIndexFiles().get(0).getAbsolutePath());
      assertEquals(shuffleFileInfos.get(i).getSize(), 10L);
    }
  }

  private void assertResult2(List<File> dataFiles, List<File> indexFiles, List<ShuffleFileInfo> shuffleFileInfos) {
    assertEquals(2, shuffleFileInfos.size());
    assertEquals(20, shuffleFileInfos.get(0).getSize());
    assertEquals(10, shuffleFileInfos.get(1).getSize());
    assertEquals(dataFiles.get(0).getAbsolutePath(), shuffleFileInfos.get(0).getDataFiles().get(0).getAbsolutePath());
    assertEquals(indexFiles.get(0).getAbsolutePath(), shuffleFileInfos.get(0).getIndexFiles().get(0).getAbsolutePath());
    assertEquals(dataFiles.get(1).getAbsolutePath(), shuffleFileInfos.get(0).getDataFiles().get(1).getAbsolutePath());
    assertEquals(indexFiles.get(1).getAbsolutePath(), shuffleFileInfos.get(0).getIndexFiles().get(1).getAbsolutePath());
    assertEquals(dataFiles.get(2).getAbsolutePath(), shuffleFileInfos.get(1).getDataFiles().get(0).getAbsolutePath());
    assertEquals(indexFiles.get(2).getAbsolutePath(), shuffleFileInfos.get(1).getIndexFiles().get(0).getAbsolutePath());
  }

  private void assertResult3(List<File> dataFiles, List<File> indexFiles, ShuffleFileInfo shuffleFileInfo) {
    for (int i = 0; i < 3; ++i) {
      assertEquals(dataFiles.get(i).getAbsolutePath(), shuffleFileInfo.getDataFiles().get(i).getAbsolutePath());
      assertEquals(indexFiles.get(i).getAbsolutePath(), shuffleFileInfo.getIndexFiles().get(i).getAbsolutePath());
    }
  }

  private void assertResult4(List<File> dataFiles, List<File> indexFiles, List<ShuffleFileInfo> shuffleFileInfos) {
    assertEquals(1, shuffleFileInfos.size());
    assertEquals(10, shuffleFileInfos.get(0).getSize());
    assertEquals(dataFiles.get(0).getAbsolutePath(), shuffleFileInfos.get(0).getDataFiles().get(0).getAbsolutePath());
    assertEquals(indexFiles.get(0).getAbsolutePath(), shuffleFileInfos.get(0).getIndexFiles().get(0).getAbsolutePath());
  }

  @Test
  public void calculateUploadTimeTest() {
    DiskItem mockDiskItem = mock(DiskItem.class);
    when(mockDiskItem.getBasePath()).thenReturn(base.getAbsolutePath());
    ShuffleUploader shuffleUploader = new ShuffleUploader.Builder()
        .diskItem(mockDiskItem)
        .uploadThreadNum(1)
        .uploadIntervalMS(3)
        .uploadCombineThresholdMB(300)
        .maxForceUploadExpireTimeS(13)
        .referenceUploadSpeedMBS(128)
        .remoteStorageType(StorageType.HDFS)
        .hdfsBathPath("hdfs://base")
        .serverId("prefix")
        .hadoopConf(new Configuration())
        .build();
    assertEquals(2, shuffleUploader.calculateUploadTime(0,0, false));
    assertEquals(2, shuffleUploader.calculateUploadTime(0, 128 * 1024, false));
    assertEquals(2, shuffleUploader.calculateUploadTime(0, 128 * 1024 * 1024, false));
    assertEquals(6, shuffleUploader.calculateUploadTime(0,3 * 128 * 1024 * 1024, false));
    assertEquals(12, shuffleUploader.calculateUploadTime(6 * 128 * 1024 * 1024,
        3 * 128 * 1024 * 1024, false));
    shuffleUploader = new ShuffleUploader.Builder()
        .diskItem(mockDiskItem)
        .uploadThreadNum(2)
        .uploadIntervalMS(3)
        .uploadCombineThresholdMB(300)
        .maxForceUploadExpireTimeS(10)
        .referenceUploadSpeedMBS(128)
        .remoteStorageType(StorageType.HDFS)
        .hdfsBathPath("hdfs://base")
        .serverId("prefix")
        .hadoopConf(new Configuration())
        .build();
    assertEquals(2, shuffleUploader.calculateUploadTime(0,0, false));
    assertEquals(2, shuffleUploader.calculateUploadTime(0,128 * 1024, false));
    assertEquals(2, shuffleUploader.calculateUploadTime(0,128 * 1024 * 1024, false));
    assertEquals(6, shuffleUploader.calculateUploadTime(0, 6 * 128 * 1024 * 1024, false));
    assertEquals(8, shuffleUploader.calculateUploadTime(4 * 128 * 1024 * 1024,
        6 * 128 * 1024 * 1024, false));

    shuffleUploader = new ShuffleUploader.Builder()
        .diskItem(mockDiskItem)
        .uploadThreadNum(2)
        .uploadIntervalMS(3)
        .uploadCombineThresholdMB(300)
        .maxForceUploadExpireTimeS(7)
        .referenceUploadSpeedMBS(128)
        .remoteStorageType(StorageType.HDFS)
        .hdfsBathPath("hdfs://base")
        .serverId("prefix")
        .hadoopConf(new Configuration())
        .build();
    assertEquals(7, shuffleUploader.calculateUploadTime(4 * 128 * 1024 * 1024,
        6 * 128 * 1024 * 1024, true));

    shuffleUploader = new ShuffleUploader.Builder()
        .diskItem(mockDiskItem)
        .uploadThreadNum(2)
        .uploadIntervalMS(3)
        .uploadCombineThresholdMB(300)
        .maxForceUploadExpireTimeS(1)
        .referenceUploadSpeedMBS(128)
        .remoteStorageType(StorageType.HDFS)
        .hdfsBathPath("hdfs://base")
        .serverId("prefix")
        .hadoopConf(new Configuration())
        .build();
    assertEquals(1, shuffleUploader.calculateUploadTime(0,0, true));
  }

  @Test
  public void uploadTest() {
    try {
      ShuffleUploader.Builder builder = new ShuffleUploader.Builder();
      DiskItem diskItem = DiskItem.newBuilder()
          .capacity(100)
          .basePath(base.getAbsolutePath())
          .highWaterMarkOfWrite(50)
          .lowWaterMarkOfWrite(45)
          .shuffleExpiredTimeoutMs(1000)
          .build();
      builder.diskItem(diskItem);
      builder.hadoopConf(new Configuration());
      builder.hdfsBathPath("hdfs://test");
      builder.referenceUploadSpeedMBS(2);
      builder.remoteStorageType(StorageType.HDFS);
      builder.serverId("test");
      builder.uploadCombineThresholdMB(1);
      builder.uploadThreadNum(1);
      builder.uploadIntervalMS(1000);
      builder.maxForceUploadExpireTimeS(1);
      ShuffleUploadHandlerFactory mockFactory = mock(ShuffleUploadHandlerFactory.class);
      ShuffleUploadHandler mockHandler = mock(ShuffleUploadHandler.class);
      when(mockFactory.createShuffleUploadHandler(any())).thenReturn(mockHandler);
      ShuffleUploadResult result0 = new ShuffleUploadResult(50, Lists.newArrayList(1, 2));
      ShuffleUploadResult result1 = new ShuffleUploadResult(90, Lists.newArrayList(1, 2, 3));
      ShuffleUploadResult result2 = new ShuffleUploadResult(10, Lists.newArrayList(1, 2));
      ShuffleUploadResult result3 = new ShuffleUploadResult(40, Lists.newArrayList(1, 3, 2, 4));
      when(mockHandler.upload(any(),any(), any())).thenReturn(result0).thenReturn(result1)
          .thenReturn(result2).thenReturn(result3);

      ShuffleUploader uploader = spy(builder.build());
      when(uploader.getHandlerFactory()).thenReturn(mockFactory);
      diskItem.createMetadataIfNotExist("key");
      diskItem.updateWrite("key", 70, Lists.newArrayList(1, 2, 3));
      File dir1 = new File(base.getAbsolutePath() + "/key/1-1/");
      dir1.mkdirs();
      File file1d = new File(base.getAbsolutePath() + "/key/1-1/test.data");
      file1d.createNewFile();
      File file1i = new File(base.getAbsolutePath() + "/key/1-1/test.index");
      file1i.createNewFile();
      File dir2 = new File(base.getAbsolutePath() + "/key/2-2/");
      dir2.mkdirs();
      File file2d = new File(base.getAbsolutePath() + "/key/2-2/test.data");
      file2d.createNewFile();
      File file2i = new File(base.getAbsolutePath() + "/key/2-2/test.index");
      file2i.createNewFile();
      File dir3 = new File(base.getAbsolutePath() + "/key/3-3/");
      dir3.mkdirs();
      File file3d = new File(base.getAbsolutePath() + "/key/3-3/test.data");
      file3d.createNewFile();
      File file3i = new File(base.getAbsolutePath() + "/key/3-3/test.index");
      file3i.createNewFile();
      byte[] data = new byte[20];
      new Random().nextBytes(data);
      try (OutputStream out = new FileOutputStream(file1d)) {
        out.write(data);
      } catch (IOException e) {
        fail(e.getMessage());
      }
      data = new byte[30];
      try (OutputStream out = new FileOutputStream(file2d)) {
        out.write(data);
      } catch (IOException e) {
        fail(e.getMessage());
      }
      data = new byte[20];
      try (OutputStream out = new FileOutputStream(file3d)) {
        out.write(data);
      } catch (IOException e) {
        fail(e.getMessage());
      }
      uploader.upload();
      assertEquals(20, diskItem.getNotUploadedSize("key"));
      assertEquals(1, diskItem.getNotUploadedPartitions("key").getCardinality());
      assertTrue(diskItem.getNotUploadedPartitions("key").contains(3));
      assertFalse(file1d.exists());
      assertFalse(file1i.exists());
      assertFalse(file2d.exists());
      assertFalse(file2i.exists());
      assertTrue(file3d.exists());
      assertTrue(file3i.exists());

      diskItem.updateWrite("key", 70, Lists.newArrayList(1, 2));
      file1d.createNewFile();
      file1i.createNewFile();
      file2d.createNewFile();
      file2i.createNewFile();
      data = new byte[30];
      new Random().nextBytes(data);
      try (OutputStream out = new FileOutputStream(file1d)) {
        out.write(data);
      } catch (IOException e) {
        fail(e.getMessage());
      }
      data = new byte[40];
      try (OutputStream out = new FileOutputStream(file2d)) {
        out.write(data);
      } catch (IOException e) {
        fail(e.getMessage());
      }
      uploader.upload();
      assertEquals(0, diskItem.getNotUploadedSize("key"));
      assertTrue(diskItem.getNotUploadedPartitions("key").isEmpty());
      assertFalse(file1d.exists());
      assertFalse(file1i.exists());
      assertFalse(file2d.exists());
      assertFalse(file2i.exists());
      assertFalse(file3d.exists());
      assertFalse(file3i.exists());

      diskItem.updateWrite("key", 30, Lists.newArrayList(1, 2, 3));
      file1d.createNewFile();
      file1i.createNewFile();
      file2d.createNewFile();
      file2i.createNewFile();
      file3i.createNewFile();
      file3d.createNewFile();
      data = new byte[5];
      new Random().nextBytes(data);
      try (OutputStream out = new FileOutputStream(file1d)) {
        out.write(data);
      } catch (IOException e) {
        fail(e.getMessage());
      }
      data = new byte[5];
      try (OutputStream out = new FileOutputStream(file2d)) {
        out.write(data);
      } catch (IOException e) {
        fail(e.getMessage());
      }
      data = new byte[20];
      try (OutputStream out = new FileOutputStream(file3d)) {
        out.write(data);
      } catch (IOException e) {
        fail(e.getMessage());
      }
      uploader.upload();
      assertEquals(30, diskItem.getNotUploadedSize("key"));
      assertEquals(3, diskItem.getNotUploadedPartitions("key").getCardinality());

      diskItem.prepareStartRead("key");
      uploader.upload();
      assertEquals(20, diskItem.getNotUploadedSize("key"));
      assertEquals(1, diskItem.getNotUploadedPartitions("key").getCardinality());
      assertTrue(file1d.exists());
      assertTrue(file1i.exists());
      assertTrue(file2d.exists());
      assertTrue(file2i.exists());
      assertTrue(file3d.exists());
      assertTrue(file3i.exists());

      diskItem.updateShuffleLastReadTs("key");
      diskItem.start();
      Uninterruptibles.sleepUninterruptibly(2, TimeUnit.SECONDS);
      assertTrue(file1d.exists());
      assertTrue(file1i.exists());
      assertTrue(file2d.exists());
      assertTrue(file2i.exists());
      assertTrue(file3d.exists());
      assertTrue(file3i.exists());

      diskItem.updateShuffleLastReadTs("key");
      diskItem.updateWrite("key", 20, Lists.newArrayList(1, 2, 4));
      data = new byte[10];
      new Random().nextBytes(data);
      try (OutputStream out = new FileOutputStream(file1d)) {
        out.write(data);
      } catch (IOException e) {
        fail(e.getMessage());
      }
      data = new byte[10];
      try (OutputStream out = new FileOutputStream(file2d)) {
        out.write(data);
      } catch (IOException e) {
        fail(e.getMessage());
      }
      uploader.upload();
      assertEquals(0, diskItem.getNotUploadedSize("key"));
      assertTrue(diskItem.getNotUploadedPartitions("key").isEmpty());
      Uninterruptibles.sleepUninterruptibly(2, TimeUnit.SECONDS);

      assertFalse(file1d.exists());
      assertFalse(file1i.exists());
      assertFalse(file2d.exists());
      assertFalse(file2i.exists());
      assertFalse(file3d.exists());
      assertFalse(file3i.exists());
      diskItem.stop();
    } catch (Exception e) {
      e.printStackTrace();
      fail();
    }
  }

  private void assertException(Class<?> c, Consumer<Void> f) {
    BiConsumer<Class<?>, Consumer<Void>> checker = (expectedExceptionClass, func) -> {
      try {
        func.accept(null);
      } catch (Exception e) {
        assertEquals(expectedExceptionClass, e.getClass());
      }
    };
    checker.accept(c, f);
  }

  private void writeFile(File f, int size) {
    byte[] data1 = new byte[size];
    new Random().nextBytes(data1);
    try (OutputStream out = new FileOutputStream(f)) {
      out.write(data1);
    } catch (IOException e) {
      fail(e.getMessage());
    }
  }

}
