/*
 * Copyright 2015 Brian Hess
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.loader;

import com.datastax.loader.parser.BooleanParser;
import com.datastax.loader.parser.CqlDelimParser;

import java.lang.System;
import java.lang.String;
import java.lang.Integer;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.Locale;
import java.io.File;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.text.ParseException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.policies.TokenAwarePolicy;
import com.datastax.driver.core.policies.DCAwareRoundRobinPolicy;


public class CqlDelimLoad {
    private String host = null;
    private int port = 9042;
    private String username = null;
    private String password = null;
    private Cluster cluster = null;
    private Session session = null;
    private int numFutures = 1000;

    private String cqlSchema = null;

    private long maxErrors = 10;
    private long skipRows = 0;
    private long maxRows = -1;
    private String badDir = null;
    private BufferedWriter badWriter = null;
    private String filename = null;

    private Locale locale = null;
    private BooleanParser.BoolStyle boolStyle = null;
    private String dateFormatString = null;
    private String nullString = null;
    private String delimiter = null;
    private boolean delimiterInQuotes = false;

    private int numThreads = 5;

    private String usage() {
	String usage = "Usage: -f <filename> -host <ipaddress> -schema <schema> [OPTIONS]\n";
	usage = usage + "OPTIONS:\n";
	usage = usage + "  -delim <delimiter>             Delimiter to use [,]\n";
	usage = usage + "  -delmInQuotes true             Set to 'true' if delimiter can be inside quoted fields [false]\n";
	usage = usage + "  -dateFormat <dateFormatString> Date format [default for Locale.ENGLISH]\n";
	usage = usage + "  -nullString <nullString>       String that signifies NULL [none]\n";
	usage = usage + "  -skipRows <skipRows>           Number of rows to skip [0]\n";
	usage = usage + "  -maxRows <maxRows>             Maximum number of rows to read (-1 means all) [-1]\n";
	usage = usage + "  -maxErrors <maxErrors>         Maximum errors to endure [10]\n";
	usage = usage + "  -badDir <badDirectory>         Directory for where to place badly parsed rows. [none]\n";
	usage = usage + "  -port <portNumber>             CQL Port Number [9042]\n";
	usage = usage + "  -user <username>               Cassandra username [none]\n";
	usage = usage + "  -pw <password>                 Password for user [none]\n";
	usage = usage + "  -numFutures <numFutures>       Number of CQL futures to keep in flight [1000]\n";
	usage = usage + "  -decimalDelim <decimalDelim>   Decimal delimiter [.] Other option is ','\n";
	usage = usage + "  -boolStyle <boolStyleString>   Style for booleans [TRUE_FALSE]\n";
	usage = usage + "  -numThreads <numThreads>       Number of concurrent threads (files) to load [5]\n";
	usage = usage + "\n\nExamples:\n";
	usage = usage + "cassandra-loader -f /path/to/file.csv -host localhost -schema \"test.test3(a int, b int, c int)\"\n";
	usage = usage + "cassandra-loader -f /path/to/directory -host 1.2.3.4 -schema \"test.test3(a int, b int, c int)\" -delim \"\\t\" -numThreads 10\n";
	usage = usage + "cassandra-loader -f stdin -host localhost -schema \"test.test3(a int, b int, c int)\" -user myuser -pw mypassword\n";
	return usage;
    }
    
    private boolean validateArgs() {
	if (0 >= numFutures) {
	    System.err.println("Number of futures must be positive");
	    return false;
	}
	if (0 > skipRows) {
	    System.err.println("Number of rows to skip must be non-negative");
	    return false;
	}
	if (0 >= maxRows) {
	    System.err.println("Maximum number of rows to load must be positive");
	    return false;
	}
	if (0 > maxErrors) {
	    System.err.println("Maximum number of errors must be non-negative");
	    return false;
	}
	if (numThreads < 1) {
	    System.err.println("Number of threads must be non-negative");
	    return false;
	}
	if (!filename.equalsIgnoreCase("stdin")) {
	    File infile = new File(filename);
	    if ((!infile.isFile()) && (!infile.isDirectory())) {
		System.err.println("The -f argument needs to be a file or a directory");
		return false;
	    }
	    if (infile.isDirectory()) {
		File[] infileList = infile.listFiles();
		if (infileList.length < 1) {
		    System.err.println("The directory supplied is empty");
		    return false;
		}
	    }
	}
	if ((null == username) && (null != password)) {
	    System.err.println("If you supply the password, you must supply the username");
	    return false;
	}
	if ((null != username) && (null == password)) {
	    System.err.println("If you supply the username, you must supply the password");
	    return false;
	}

	return true;
    }
    
    private boolean parseArgs(String[] args) {
	if (args.length == 0) {
	    System.err.println("No arguments specified");
	    return false;
	}
	if (0 != args.length % 2)
	    return false;
	Map<String, String> amap = new HashMap<String,String>();
	for (int i = 0; i < args.length; i+=2)
	    amap.put(args[i], args[i+1]);

	host = amap.remove("-host");
	if (null == host) { // host is required
	    System.err.println("Must provide a host");
	    return false;
	}

	filename = amap.remove("-f");
	if (null == filename) { // filename is required
	    System.err.println("Must provide a filename/directory");
	    return false;
	}

	cqlSchema = amap.remove("-schema");
	if (null == cqlSchema) { // schema is required
	    System.err.println("Must provide a schema");
	    return false;
	}

	String tkey;
	if (null != (tkey = amap.remove("-port")))          port = Integer.parseInt(tkey);
	if (null != (tkey = amap.remove("-user")))          username = tkey;
	if (null != (tkey = amap.remove("-pw")))            password = tkey;
	if (null != (tkey = amap.remove("-numFutures")))    numFutures = Integer.parseInt(tkey);
	if (null != (tkey = amap.remove("-maxErrors")))     maxErrors = Integer.parseInt(tkey);
	if (null != (tkey = amap.remove("-skipRows")))      skipRows = Integer.parseInt(tkey);
	if (null != (tkey = amap.remove("-maxRows")))       maxRows = Integer.parseInt(tkey);
	if (null != (tkey = amap.remove("-badDir")))        badDir = tkey;
	if (null != (tkey = amap.remove("-dateFormat")))    dateFormatString = tkey;
	if (null != (tkey = amap.remove("-nullString")))    nullString = tkey;
	if (null != (tkey = amap.remove("-delim")))         delimiter = tkey;
	if (null != (tkey = amap.remove("-delimInQuotes"))) delimiterInQuotes = Boolean.parseBoolean(tkey);
	if (null != (tkey = amap.remove("-decimalDelim"))) {
	    if (tkey.equals(","))
		locale = Locale.FRANCE;
	}
	if (null != (tkey = amap.remove("-boolStyle"))) {
	    boolStyle = BooleanParser.getBoolStyle(tkey);
	    if (null == boolStyle) {
		System.err.println("Bad boolean style.  Options are: " + BooleanParser.getOptions());
		return false;
	    }
	}
	if (null != (tkey = amap.remove("-numThreads")))       numThreads = Integer.parseInt(tkey);
	if (-1 == maxRows)
	    maxRows = Long.MAX_VALUE;
	if (-1 == maxErrors)
	    maxErrors = Long.MAX_VALUE;
	
	if (!amap.isEmpty()) {
	    for (String k : amap.keySet())
		System.err.println("Unrecognized option: " + k);
	    return false;
	}
	return validateArgs();
    }

    private void setup() {
	// Connect to Cassandra
	Cluster.Builder clusterBuilder = Cluster.builder()
	    .addContactPoint(host)
	    .withPort(port)
	    .withLoadBalancingPolicy(new TokenAwarePolicy( new DCAwareRoundRobinPolicy()));
	if (null != username)
	    clusterBuilder = clusterBuilder.withCredentials(username, password);
	cluster = clusterBuilder.build();
	session = cluster.newSession();
    }

    private void cleanup() {
	if (null != session)
	    session.close();
	if (null != cluster)
	    cluster.close();
    }
    
    public boolean run(String[] args) throws IOException, ParseException, InterruptedException, ExecutionException {
	if (false == parseArgs(args)) {
	    System.err.println("Bad arguments");
	    System.err.println(usage());
	    return false;
	}

	// Setup
	setup();
	
	// open file
	BufferedReader reader = null;
	String readerName = "";
	Deque<File> fileList = new ArrayDeque<File>();
	if (filename.equalsIgnoreCase("stdin")) {
	    reader = new BufferedReader(new InputStreamReader(System.in));
	    readerName = "stdin";
	}
	else {
	    File infile = new File(filename);
	    if (infile.isFile()) {
		reader = new BufferedReader(new FileReader(infile));
		readerName = filename;
	    }
	    else {
		File[] inFileList = infile.listFiles();
		if (inFileList.length < 1)
		    throw new IOException("directory is empty");
		for (int i = 0; i < inFileList.length; i++)
		    fileList.push(inFileList[i]);
	    }
	}

	// Launch Threads
	ExecutorService executor;
	long total = 0;
	if (null != reader) {
	    // One file/stdin to process
	    executor = Executors.newSingleThreadExecutor();
	    Callable<Long> worker = new ThreadExecute(cqlSchema, delimiter, 
						      nullString,
						      delimiterInQuotes, 
						      dateFormatString, 
						      boolStyle, locale, 
						      maxErrors, skipRows,
						      maxRows, badDir, reader, 
						      readerName,
						      session, numFutures);
	    Future<Long> res = executor.submit(worker);
	    total = res.get();
	    executor.shutdown();
	}
	else {
	    executor = Executors.newFixedThreadPool(numThreads);
	    Set<Future<Long>> results = new HashSet<Future<Long>>();
	    while (!fileList.isEmpty()) {
		File tFile = fileList.pop();
		BufferedReader r = new BufferedReader(new FileReader(tFile));
		String rName = tFile.getName();
		Callable<Long> worker = new ThreadExecute(cqlSchema, delimiter,
							  nullString,
							  delimiterInQuotes, 
							  dateFormatString, 
							  boolStyle, locale, 
							  maxErrors, skipRows,
							  maxRows, badDir, r, 
							  rName, session,
							  numFutures);
		results.add(executor.submit(worker));
	    }
	    executor.shutdown();
	    for (Future<Long> res : results)
		total += res.get();
	}
	System.err.println("Total rows inserted: " + total);

	// Cleanup
	cleanup();

	return true;
    }

    public static void main(String[] args) throws IOException, ParseException, InterruptedException, ExecutionException {
	CqlDelimLoad cdl = new CqlDelimLoad();
	cdl.run(args);
    }

    class ThreadExecute implements Callable<Long> {
	private Session session;
	private String insert;
	private PreparedStatement statement;
	private CqlDelimParser cdp;
	private long maxErrors;
	private long skipRows;
	private long maxRows;
	private String badDir;
	private BufferedWriter badWriter = null;
	private BufferedReader reader;
	private String readerName;
	private int numFutures;
	private long numInserted;

	private String cqlSchema;
	private Locale locale = null;
	private BooleanParser.BoolStyle boolStyle = null;
	private String nullString = null;
	private String delimiter = null;
	private boolean delimiterInQuotes;

	public ThreadExecute(String inCqlSchema, String inDelimiter, 
			     String inNullString, boolean inDelimiterInQuotes, 
			     String inDateFormatString,
			     BooleanParser.BoolStyle inBoolStyle, 
			     Locale inLocale, 
			     long inMaxErrors, long inSkipRows, long inMaxRows,
			     String inBadDir, BufferedReader inReader,
			     String inReaderName, Session inSession,
			     int inNumFutures) {
	    super();
	    cqlSchema = inCqlSchema;
	    delimiter = inDelimiter;
	    nullString = inNullString;
	    delimiterInQuotes = inDelimiterInQuotes;
	    dateFormatString = inDateFormatString;
	    boolStyle = inBoolStyle;
	    locale = inLocale;
	    maxErrors = inMaxErrors;
	    skipRows = inSkipRows;
	    maxRows = inMaxRows;
	    badDir = inBadDir;
	    reader = inReader;
	    readerName = inReaderName;
	    session = inSession;
	    numFutures = inNumFutures;
	}

	public Long call() throws IOException, ParseException {
	    setup();
	    numInserted = execute();
	    cleanup();
	    return numInserted;
	}

	private void setup() throws IOException, ParseException {
	    // Prepare Badfile
	    if (null != badDir)
		badWriter = new BufferedWriter(new FileWriter(badDir + "/" 
							      + readerName
							      + ".BAD"));
	    
	    cdp = new CqlDelimParser(cqlSchema, delimiter, nullString, 
				     delimiterInQuotes, dateFormatString, 
				     boolStyle, locale);
	    insert = cdp.generateInsert();
	    statement = session.prepare(insert);
	}
	
	private void cleanup() throws IOException {
	    if (null != badWriter)
		badWriter.close();
	}

	private long execute() throws IOException {
	    List<ResultSetFuture> futures = new ArrayList<ResultSetFuture>();
	    String line;
	    int lineNumber = 0;
	    int numInserted = 0;
	    int numErrors = 0;
	    List<Object> elements;

	    System.err.println("*** Processing " + readerName);
	    while ((line = reader.readLine()) != null) {
		lineNumber++;
		if (skipRows > 0) {
		    skipRows--;
		    continue;
		}
		if (maxRows-- < 0)
		    break;
		
		if (0 == line.trim().length())
		    continue;
		
		if (0 == lineNumber % numFutures)
		    purgeFutures(futures);
		
		if (null != (elements = cdp.parse(line))) {
		    BoundStatement bind = statement.bind(elements.toArray());
		    ResultSetFuture resultSetFuture = session.executeAsync(bind);
		    futures.add(resultSetFuture);
		    numInserted++;
		}
		else {
		    System.err.println(String.format("Error parsing line %d in %s: %s", lineNumber, readerName, line));
		    if (null != badWriter) {
			badWriter.write(line);
			badWriter.newLine();
		    }
		    numErrors++;
		    if (maxErrors <= numErrors) {
			System.err.println(String.format("Maximum number of errors exceeded (%d) for %s", numErrors, readerName));
			cluster.close();
			return -1;
		    }
		}
	    }
	    purgeFutures(futures);
	    
	    System.err.println("*** DONE: " + filename + "  number of lines processed: " + lineNumber + " (" + numInserted + " inserted)");

	    return numInserted;
	}

	private void purgeFutures(List<ResultSetFuture> futures) {
	    for (ResultSetFuture future: futures) {
		future.getUninterruptibly();
	    }
	    futures.clear();
	}

	long getNumInserted() {
	    return numInserted;
	}
    }
}

