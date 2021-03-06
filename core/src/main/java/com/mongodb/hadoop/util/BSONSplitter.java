package com.mongodb.hadoop.util;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.hadoop.input.BSONFileRecordReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.*;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import org.bson.BSONObject;
import org.bson.BasicBSONCallback;
import org.bson.BasicBSONEncoder;
import org.bson.BasicBSONDecoder;
import org.bson.LazyBSONObject;
import org.bson.LazyBSONCallback;
import org.bson.LazyBSONDecoder;

public class BSONSplitter extends Configured implements Tool {

    private static final Log log = LogFactory.getLog( BSONSplitter.class );

    private Map<Path, List<FileSplit>> splitsMap;
    private Path inputPath;
    private BasicBSONCallback callback = new BasicBSONCallback();
    private LazyBSONCallback lazyCallback = new LazyBSONCallback();
    private LazyBSONDecoder lazyDec = new LazyBSONDecoder();
    private BasicBSONDecoder bsonDec = new BasicBSONDecoder();
    private BasicBSONEncoder bsonEnc = new BasicBSONEncoder();

    public static class NoSplitFileException extends Exception{}

    private static final PathFilter hiddenFileFilter = new PathFilter(){
        public boolean accept(Path p){
            String name = p.getName(); 
            return !name.startsWith("_") && !name.startsWith("."); 
        }
    }; 


    public Path[] getInputPaths(){
        String dirs = getConf().get("mapred.input.dir", "");
        String [] list = StringUtils.split(dirs);
        Path[] result = new Path[list.length];
        for (int i = 0; i < list.length; i++) {
            result[i] = new Path(StringUtils.unEscapeString(list[i]));
        }
        return result;
    }



    public BSONSplitter(){
        this.splitsMap = new HashMap<Path, List<FileSplit>>();
    }

    public void setInputPath(Path p){
        this.inputPath = p;
    }

    public ArrayList<FileSplit> getAllSplits(){
        if(splitsMap == null) return new ArrayList<FileSplit>(0);
        ArrayList<FileSplit> collectedSplits = new ArrayList<FileSplit>();
        for(List<FileSplit> fileSplits : splitsMap.values()){
            collectedSplits.addAll(fileSplits);
        }
        return collectedSplits;
    }

    public FileSplit createFileSplitFromBSON(BSONObject obj, FileSystem fs, FileStatus inputFile) throws IOException{//{{{
        long start = (Long)obj.get("s");
        long splitLen = (Long)obj.get("l");
        try{
            BlockLocation[] blkLocations = fs.getFileBlockLocations(inputFile, start, splitLen);
            int blockIndex = getLargestBlockIndex(blkLocations);
            return new FileSplit(inputFile.getPath(), start, splitLen, blkLocations[blockIndex].getHosts());
        }catch(IOException e){
            log.warn("Couldn't find block locations when constructing input split from BSON. Using non-block-aware input split; " + e.getMessage());
            return new FileSplit(inputFile.getPath(), start, splitLen, null);
        }
    }//}}}

    public FileSplit createFileSplit(FileStatus inFile, FileSystem fs, long splitStart, long splitLen){//{{{
        try{
            BlockLocation[] blkLocations = fs.getFileBlockLocations(inFile, splitStart, splitLen);
            int blockIndex = getLargestBlockIndex(blkLocations);
            return new FileSplit(inFile.getPath(), splitStart, splitLen, blkLocations[blockIndex].getHosts());
        }catch(IOException e){
            log.warn("Couldn't find block locations when constructing input split from byte offset. Using non-block-aware input split; " + e.getMessage());
            return new FileSplit(inFile.getPath(), splitStart, splitLen, null);
        }
    }//}}}

    public void loadSplitsFromSplitFile(FileStatus inputFile, Path splitFile) throws NoSplitFileException, IOException{//{{{
        List<FileSplit> splits = new ArrayList<FileSplit>();
        FileSystem fs = splitFile.getFileSystem(getConf()); // throws IOException
        FileStatus splitFileStatus = null;
        try{
            splitFileStatus = fs.getFileStatus(splitFile);
            log.info("Found split file at : " + splitFileStatus.toString());
        }catch(Exception e){
            throw new NoSplitFileException();
        }
        FSDataInputStream fsDataStream = fs.open(splitFile); // throws IOException
        while(fsDataStream.getPos() < splitFileStatus.getLen()){
            callback.reset();
            bsonDec.decode(fsDataStream, callback);
            BSONObject splitInfo = (BSONObject)callback.get();
            splits.add(createFileSplitFromBSON(splitInfo, fs, inputFile));
        }
        this.splitsMap.put(inputFile.getPath(), splits);
    }//}}}

    public void readSplitsForFile(FileStatus file) throws IOException{
        long minSize = Math.max(1L, getConf().getLong("mapred.min.split.size", 1L));
        long maxSize = getConf().getLong("mapred.max.split.size", Long.MAX_VALUE);
        Path path = file.getPath();
        List<FileSplit> splits = new ArrayList<FileSplit>();
        FileSystem fs = path.getFileSystem(getConf());
        long length = file.getLen();
        if (length != 0) { 
            int numDocsRead = 0;
            long blockSize = file.getBlockSize();
            long splitSize = Math.max(minSize, Math.min(maxSize, blockSize));
            log.info("Generating splits for " + path + " of up to " + splitSize + " bytes.");
            long bytesRemaining = length;
            int numDocs = 0;
            FSDataInputStream fsDataStream = fs.open(path);
            long curSplitLen = 0;
            long curSplitStart = 0;
            long curSplitEnd = 0;
            try{
                while(fsDataStream.getPos() + 1 < length){
                    lazyCallback.reset();
                    int bytesRead = lazyDec.decode(fsDataStream, lazyCallback);
                    LazyBSONObject bo = (LazyBSONObject)lazyCallback.get();
                    int bsonDocSize = bo.getBSONSize();
                    if(curSplitLen + bsonDocSize >= splitSize){
                        FileSplit split = createFileSplit(file, fs, curSplitStart, curSplitLen);
                        splits.add(split);
                        log.info("Creating new split (" + splits.size() + ") " + split.toString());
                        curSplitStart = fsDataStream.getPos() - bsonDocSize;
                        curSplitLen = 0;
                    }
                    curSplitLen += bsonDocSize;
                    numDocsRead++;
                    if(numDocsRead % 1000 == 0){
                        float splitProgress = 100f * (fsDataStream.getPos() / length);
                        log.info("Read " + numDocsRead + " docs calculating splits for " + file.toString() + "; " + splitProgress + "% complete.");
                    }
                }
                if(curSplitLen > 0){
                    FileSplit split = createFileSplit(file, fs, curSplitStart, curSplitLen);
                    splits.add(split);
                    log.info("Final split (" + splits.size() + ") " + split.toString());
                }
                this.splitsMap.put(path, splits);
                log.info("Completed splits calculation for " + file.toString());
            }catch(IOException e){
            }finally{
                fsDataStream.close();
            }
        }else{
            log.warn("Zero-length file, skipping split calculation.");
        }
    }

    public void writeSplits() throws IOException{
        pathsloop:
        for(Map.Entry<Path, List<FileSplit>> entry : this.splitsMap.entrySet()) {
            Path key = entry.getKey();
            List<FileSplit> value = entry.getValue();
            Path outputPath = new Path(key.getParent(),  "." + key.getName() + ".splits");
            FileSystem pathFileSystem = outputPath.getFileSystem(getConf());
            FSDataOutputStream fsDataOut = null;
            try{
                fsDataOut = pathFileSystem.create(outputPath, false);
                for(FileSplit inputSplit : value){
                    BSONObject splitObj = BasicDBObjectBuilder.start()
                                            .add( "s" , inputSplit.getStart())
                                            .add( "l" , inputSplit.getLength()).get();
                    byte[] encodedObj = this.bsonEnc.encode(splitObj);
                    try{
                        fsDataOut.write(encodedObj, 0, encodedObj.length);
                    }catch(IOException ioe){
                        log.error("Failed writing data to splits file:" + ioe.getMessage());
                        continue pathsloop;
                    }

                }
            }catch(IOException e){
                log.error("Could not create splits file: " + e.getMessage());
                continue;
            }finally{
                if(fsDataOut!=null){
                    fsDataOut.close();
                }
            }
        }
    }
    
    public void readSplits() throws IOException{
        this.splitsMap.clear();
        if(this.inputPath == null){
            throw new IllegalStateException("Input path has not been set.");
        }
        //for(Path p : getInputPaths()){
        FileSystem fs = this.inputPath.getFileSystem(getConf()); 
        FileStatus file = fs.getFileStatus(this.inputPath);
        readSplitsForFile(file);
    }

    public Map<Path, List<FileSplit>> getSplitsMap(){
        return this.splitsMap;
    }


    public List<FileStatus> getFilesInPath(Path p) throws IOException{
        ArrayList<FileStatus> result = new ArrayList<FileStatus>();
        FileSystem fs = p.getFileSystem(getConf()); 
        FileStatus[] matches = fs.globStatus(p, hiddenFileFilter);
        if (matches == null) {
            throw new IOException("Input path does not exist: " + p);
        } else if (matches.length == 0) {
            throw new IOException("Input Pattern " + p + " matches 0 files");
        } else {
            for (FileStatus globStat: matches) {
                if (globStat.isDir()) {
                    // skip directories.
                    //for(FileStatus stat: fs.listStatus(globStat.getPath(), hiddenFileFilter)) {
                        //result.add(stat);
                    //}          
                } else {
                    result.add(globStat);
                }
            }
        }
        return result;
    }

    public void testReadFile() throws IOException{
        Path path = this.inputPath;
        FileSystem fs = path.getFileSystem(getConf());
        FileStatus file = fs.getFileStatus(path);
        FSDataInputStream fsDataStream = fs.open(path);
        long length = file.getLen();
        while(fsDataStream.getPos() < length){
            byte[] headerBuf = new byte[4];
            fsDataStream.read(fsDataStream.getPos(), headerBuf, 0, 4);
            fsDataStream.skip(1000);
            //fsDataStream.seek(fsDataStream.getPos() + 1000);
        }
    }

    public int run( String[] args ) throws Exception{
        this.setInputPath(new Path(getConf().get("mapred.input.dir", "")));
        readSplits();
        writeSplits();
        return 0;
    }

    public static int getLargestBlockIndex(BlockLocation[] blockLocations){//{{{
        int retVal = -1;
        if( blockLocations == null ){
            return retVal;
        }
        long max = 0;
        for(int i=0;i<blockLocations.length;i++){
            BlockLocation blk = blockLocations[i];
            if(blk.getLength() > max){
                retVal = i;
            }
        }
        return retVal;
    }//}}}

    public static void main(String args[]) throws Exception{
        System.exit( ToolRunner.run( new BSONSplitter(), args ) );
    }

}
