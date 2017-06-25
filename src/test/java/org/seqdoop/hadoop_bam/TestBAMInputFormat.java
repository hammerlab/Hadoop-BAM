package org.seqdoop.hadoop_bam;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.samtools.util.Interval;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.task.JobContextImpl;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.junit.Test;
import org.seqdoop.hadoop_bam.util.WrapSeekable;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class TestBAMInputFormat {
  private String input;
  private TaskAttemptContext taskAttemptContext;
  private JobContext jobContext;
  
  private void completeSetup(List<Interval> intervals) {
    Configuration conf = new Configuration();
    conf.set("mapred.input.dir", "file://" + input);
    if (intervals != null) {
      BAMInputFormat.setIntervals(conf, intervals);
    }
    taskAttemptContext = new TaskAttemptContextImpl(conf, mock(TaskAttemptID.class));
    jobContext = new JobContextImpl(conf, taskAttemptContext.getJobID());
  }

  @Test
  public void testNoReadsInFirstSplitBug() throws Exception {
    input = BAMTestUtil.writeBamFileWithLargeHeader().getAbsolutePath();
    completeSetup(null);
    BAMInputFormat inputFormat = new BAMInputFormat();
    List<InputSplit> splits = inputFormat.getSplits(jobContext);
    assertEquals(1, splits.size());
  }

  @Test
  public void testMultipleSplits() throws Exception {
    input = BAMTestUtil.writeBamFile(1000, SAMFileHeader.SortOrder.queryname)
        .getAbsolutePath();
    completeSetup(null);
    jobContext.getConfiguration().setInt(FileInputFormat.SPLIT_MAXSIZE, 40000);
    BAMInputFormat inputFormat = new BAMInputFormat();
    List<InputSplit> splits = inputFormat.getSplits(jobContext);

    assertEquals(2, splits.size());
    List<SAMRecord> split0Records = getSAMRecordsFromSplit(inputFormat, splits.get(0));
    List<SAMRecord> split1Records = getSAMRecordsFromSplit(inputFormat, splits.get(1));
    assertEquals(1629, split0Records.size());
    assertEquals(371, split1Records.size());
    SAMRecord lastRecordOfSplit0 = split0Records.get(split0Records.size() - 1);
    SAMRecord firstRecordOfSplit1 = split1Records.get(0);
    assertEquals(lastRecordOfSplit0.getReadName(), firstRecordOfSplit1.getReadName());
    assertTrue(lastRecordOfSplit0.getFirstOfPairFlag());
    assertTrue(firstRecordOfSplit1.getSecondOfPairFlag());
  }

  @Test
  public void testIntervals() throws Exception {
    input = BAMTestUtil.writeBamFile(1000, SAMFileHeader.SortOrder.coordinate)
        .getAbsolutePath();
    List<Interval> intervals = new ArrayList<Interval>();
    intervals.add(new Interval("chr21", 5000, 9999)); // includes two unpaired fragments
    intervals.add(new Interval("chr21", 20000, 22999));

    completeSetup(intervals);

    jobContext.getConfiguration().setInt(FileInputFormat.SPLIT_MAXSIZE, 40000);
    BAMInputFormat inputFormat = new BAMInputFormat();
    List<InputSplit> splits = inputFormat.getSplits(jobContext);
    assertEquals(1, splits.size());
    List<SAMRecord> split0Records = getSAMRecordsFromSplit(inputFormat, splits.get(0));
    assertEquals(16, split0Records.size());
  }

  @Test
  public void testIntervalCoveringWholeChromosome() throws Exception {
    input = BAMTestUtil.writeBamFile(1000, SAMFileHeader.SortOrder.coordinate)
        .getAbsolutePath();
    List<Interval> intervals = new ArrayList<Interval>();
    intervals.add(new Interval("chr21", 1, 1000135));

    completeSetup(intervals);

    jobContext.getConfiguration().setInt(FileInputFormat.SPLIT_MAXSIZE, 40000);
    BAMInputFormat inputFormat = new BAMInputFormat();
    List<InputSplit> splits = inputFormat.getSplits(jobContext);

    assertEquals(2, splits.size());
    List<SAMRecord> split0Records = getSAMRecordsFromSplit(inputFormat, splits.get(0));
    List<SAMRecord> split1Records = getSAMRecordsFromSplit(inputFormat, splits.get(1));
    assertEquals(1629, split0Records.size());
    assertEquals(371, split1Records.size());
  }

//  @Test
//  public void testTrickySplit() throws Exception {
//    Path path = new Path("file:///Users/ryan/c/hl/hadoop-bam/src/test/resources/1.bam");
//    Configuration conf = new Configuration();
//    SeekableStream ss = WrapSeekable.openPath(conf, path);
//    BAMSplitGuesser guesser = new BAMSplitGuesser(ss, conf);
//    guesser.fillBuffer(15071);
//    assertEquals(65469, guesser.findNextBAMPos(0, 65469));
//  }

  private List<SAMRecord> getSAMRecordsFromSplit(BAMInputFormat inputFormat,
      InputSplit split) throws Exception {
    RecordReader<LongWritable, SAMRecordWritable> reader = inputFormat
        .createRecordReader(split, taskAttemptContext);
    reader.initialize(split, taskAttemptContext);

    List<SAMRecord> records = new ArrayList<SAMRecord>();
    while (reader.nextKeyValue()) {
      SAMRecord r = reader.getCurrentValue().get();
      records.add(r);
    }
    return records;
  }
}
