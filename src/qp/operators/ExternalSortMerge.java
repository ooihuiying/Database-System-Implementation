/**
 * ExternalSort algorithm
 **/

package qp.operators;

import qp.utils.Batch;
import qp.utils.SortedRunComparator;
import qp.utils.Tuple;
import qp.utils.TupleReader;
import qp.utils.TupleWriter;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ExternalSortMerge extends Operator {

	private static int NUMBER_OF_EXTERNALSORT = 0;
	private int number;
	
	private int fileNum = 0;
	private int roundNum = 0;
	private File finalSortedFile;
	private TupleReader finalSortedFileReader;
	
	private Operator source;
	private int numBuffers;
	private ArrayList<Integer> listOfConditions;
	private Comparator<Tuple> tuplesComparator;
	
	private int tupleSize;
	private int numOfBatch;
	
	/**
	 * Sorts tuples using multi-way merge sort algorithm.
	 * num of passes = 1 + ceil( log(ceil(N/B)) / log(B-1) )
	 * total IO cost = 2N * num of passes
	 */
     public ExternalSortMerge(Operator source, ArrayList<Integer> listOfConditions, int numBuffers) {
    	 super(OpType.SORT);
    	 this.number = NUMBER_OF_EXTERNALSORT++;
    	 this.source = source;
    	 this.listOfConditions = listOfConditions;
    	 this.numBuffers = numBuffers;
     }

     /**
      * Read the file and generate sorted runs
      * After generate the sorted runs it will begin merging the sorted runs
      */
     @Override
     public boolean open() {
    	 if (!source.open()) {
    		 return false;
    	 }
    	 tuplesComparator =  new SortedRunComparator(listOfConditions);
    	 tupleSize = source.getSchema().getTupleSize();
    	 numOfBatch = Batch.getPageSize() / tupleSize;
  
    	 List<File> sortedRunFiles = generateSortedRuns();	// Phase 1
    	 roundNum++;
    	 fileNum = 0;
    	 finalSortedFile = merge(sortedRunFiles);	// Phase 2
    	 finalSortedFileReader = new TupleReader (finalSortedFile.toString(), Batch.getPageSize());
    	 return finalSortedFileReader.open();
    	 //return true;
     }
     
     /**
      * Return a sorted batch
      */
     public Batch next() {
         /** The file reached its end and no more to read **/
    	 
    	 if (finalSortedFileReader.peek() == null) {
    		 return null;
    	 }
         Batch tuples = new Batch(numOfBatch);
         
         while (!tuples.isFull() && finalSortedFileReader.peek() != null) {
         	if(finalSortedFileReader.peek() != null) {
         		tuples.add(finalSortedFileReader.next());
         	} 
         }
         return tuples;
     }
     
     /**
      * return the next tuple to be read and delete after read
      */
     public Tuple nextTuple() {
    	 return finalSortedFileReader.next();
     }
	
     /**
      * return the next tuple
      */
     public Tuple peekTuple() {
    	 return finalSortedFileReader.peek();
     } 
	
     /**
      * Close the operator
      */
     @Override
     public boolean close() {
    	 finalSortedFile.delete();
    	 finalSortedFileReader.close();
    	 return super.close();
     }
	
     /**
      * Generate Sorted Runs for phrase 1
      * Number of sorted run will depend on how many tuple can fit into a page
      * And the number of buffer available 
      */
     private List<File> generateSortedRuns() {
    	 List<File> sortedRunFiles = new ArrayList<>();
    	 Batch currentBatch = source.next();
    	 while (currentBatch != null) {
    		 ArrayList<Batch> currentRun = new ArrayList<>();
    		 for (int i = 0; i < numBuffers; i++) {
    			 currentRun.add(currentBatch);
    			 currentBatch = source.next();
    			 if (currentBatch == null) {
    				 break;
    			 }
    		 }
    		 List<Tuple> sortedRun = sortedRun(currentRun);
    		 File file = writeRun(sortedRun); 
    		 sortedRunFiles.add(file);
    	 }
    	 return sortedRunFiles;
     }
	
     /**
      * Merge tuples for phrase 1
      */
     private List<Tuple> sortedRun(ArrayList<Batch> run) {
    	 List<Tuple> tuples = new ArrayList<>();
    	 for (Batch batch: run) {
    		 for (int i = 0; i < batch.size(); i++) {
    			 tuples.add(batch.get(i));
    		 }            
    	 }
    	 Collections.sort(tuples, tuplesComparator);
    	 return tuples;
     }
	
     /**
      * Save each run for future uses
      */
     private File writeRun(List<Tuple> run) {
    	 String fileName = "ExternalSort" + number + ",rN" + roundNum + ",fN" + fileNum;
    	 fileNum++;
    	 File temp = new File(fileName);
    	 TupleWriter toWrite = new TupleWriter(fileName, numOfBatch);
    	 toWrite.open();
    	 for (Tuple tuple : run) {
    		 toWrite.next(tuple);
    	 }
    	 toWrite.close();
    	 return temp;
     }
	
     /**
      *  Merging runs for phrase 2
      * While the number of sorted run files is bigger than 1
      * * Allocate the files to be merged
      * And clear unwanted files
      */
     private File merge(List<File> sortedRunFiles) {
    	 int numBuff = numBuffers - 1;
	
    	 while (sortedRunFiles.size() > 1) {
    		 int numberOfSortedRuns = sortedRunFiles.size();
    		 List<File> newSortedRuns = new ArrayList<>();
    		 int count = 0;
    		 List<File> runsToSort = new ArrayList<>();
    		 for (int i = 0; i < numberOfSortedRuns; i++) {
    			 runsToSort.add(sortedRunFiles.get(i));
    			 count++;
    			 if (count == numBuff) {
    				 File resultSortedRun = mergeSortedRuns(runsToSort);
    				 newSortedRuns.add(resultSortedRun);
    				 runsToSort = new ArrayList<>();
    				 count = 0;
    			 }
    		 }
    		 if (count != 0) {
    			 File resultSortedRun = mergeSortedRuns(runsToSort);
    			 newSortedRuns.add(resultSortedRun);
    		 }
    		 roundNum++;
    		 fileNum = 0;
    		 clearRuns(sortedRunFiles);
    		 sortedRunFiles = newSortedRuns;
    	 }
    	 return sortedRunFiles.get(0);
     }
	
     /**
      * Mering for phrase 2
      * Open each file and perform merging to one bigger file
      */
     private File mergeSortedRuns(List<File> sortedRuns) {
    	 if (sortedRuns.isEmpty()) {
    		 return null;
    	 }
    	 List<Tuple> tuples = new ArrayList<>();
    	 for (File sortedRun: sortedRuns) {
    		 TupleReader reader = new TupleReader(sortedRun.toString(), numBuffers - 1);
    		 reader.open();
    		 while (!reader.isEOF()) {
    			 tuples.add(reader.next());
    		 }
    		 reader.close();
    	 }
    	 Collections.sort(tuples, tuplesComparator);
	    
    	 File file = writeRun(tuples);
    	 return file;
     }
	
     
     /**
      * Delete file after use
      */
     private void clearRuns(List<File> sortedRuns) {
    	 for (File run : sortedRuns) {
    		 run.delete();
    	 }
     }
}