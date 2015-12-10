package edu.iu.gp;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;
import org.apache.log4j.Logger;
import org.genepattern.drm.DrmJobRecord;

/**
 *
 * @author lewu@iu.edu
 */
public class PBS {

    private static final Logger log = Logger.getLogger(PBS.class); 
    
    public static String qsub(String input) throws IOException, InterruptedException, PbsException {

        Process p = Runtime.getRuntime().exec(input);

        p.waitFor();

        BufferedInputStream ef = new BufferedInputStream(p.getErrorStream());

        if (ef.available() > 0) {
            byte[] errdata = new byte[ef.available()];
            ef.read(errdata, 0, ef.available());
            String st = new String(errdata);
            ef.close();
            p.getOutputStream().close();
            p.getInputStream().close();
            throw new PbsException(st);
        }

        //return new String(data);
        BufferedInputStream b = new BufferedInputStream(p.getInputStream());
        byte[] data = new byte[b.available()];
        b.read(data);
        p.getOutputStream().close();
        p.getErrorStream().close();

        b.close();
        return new String(data).replaceAll("\n", "");
    }

    public static String qstat(DrmJobRecord drmJobRecord) throws IOException, InterruptedException, PbsException {

        final String drmJobId = drmJobRecord.getExtJobId();

        //String workDirPath = drmJobId.split("__")[0];
        String workDirPath = drmJobRecord.getWorkingDir().toString();
        //String gpId = drmJobId.split("__")[1];
        String pbsId = drmJobId.split("__")[0];
        String clusterName = drmJobId.split("__")[1];
        String pbsJobId = pbsId + "@" + clusterName;

        //String pbsJobId = jobID + "@m1.mason.indiana.edu";
        Process p = Runtime.getRuntime().exec("qstat -f " + pbsJobId);
        p.waitFor();

        BufferedInputStream errStream = new BufferedInputStream(p.getErrorStream());

        if (errStream.available() > 0) {
            byte[] errdata = new byte[errStream.available()];
            errStream.read(errdata, 0, errStream.available());
            p.getOutputStream().close();
            p.getErrorStream().close();
            errStream.close();

            // If we got errors, such as "Unknown Job Id Error 206815.m1", while checking the job status,
            // it is possible that this job has been finished long time ago and 
            // its record has been removed from the pbs history.
            // We will need to check whether the stderr, stdout, and epilogue.pbs have been write to
            // job working directory.
            File stderr = drmJobRecord.getStderrFile();
            File epilogueOut = new File(new File(workDirPath,".pbs"), ".epilogue.pbs");
            log.error(new String("we have error stream" + errdata));

            if (epilogueOut.exists()) {
                
                log.error(new String("we have error stream and epilogue file exist"));
                String epiData = (new Scanner(epilogueOut)).useDelimiter("\\z").next();
                String exitCode = getKeyValue("Job_Exit_Code", epiData.split("\n"));
                log.error(new String("we get the exitcode=" + exitCode));

                // job finished successfully, we got exit_code = 0
                if (exitCode.compareToIgnoreCase("0") == 0) {

                    // PBS script finshed successfully, but we need to do one more check on
                    // stderr file to see whether there are some error messages shown.
                    // We use some pre-defined key words for checking the erros.
                    //
                    // If we have the stderr file
                    if (stderr.exists()) {
                        if (hasErrorsInStdout(stderr)) {
                            return "F";
                        } else {
                            return "C";
                        }
                    } // stderr does not exist, it means everything looking good. 
                    else {
                        return "C";
                    }
                } // we got non-zero job exit code or we got null 
                else {
                    return "F";
                }
            }

            // There are some problems for getting this job's status
            // and we can not find stdout and epiloigue files
            log.error(new String("we got error whjle checking job status and can not find epilogue output file"));
            throw new PbsException((new String(errdata)));
        }

        BufferedInputStream ef = new BufferedInputStream(p.getInputStream());
        byte[] data = new byte[ef.available()];
        ef.read(data, 0, ef.available());
        ef.close();
        p.getOutputStream().close();
        p.getErrorStream().close();
        String Result = new String(data);
        //log.error(new String("we are good to get the job status, prepare parsing"));
        return getKeyValue("job_state", Result.split("\n"));

    }

    public static String showstart(DrmJobRecord drmJobRecord) throws IOException, InterruptedException, PbsException {

        final String drmJobId = drmJobRecord.getExtJobId();

        String pbsId = drmJobId.split("__")[0];
        String clusterName = drmJobId.split("__")[1];

        // we don't need the clusterName to use on mason
        // But it may not be the case for other system
        //String pbsJobId = jobID + "@m1.mason.indiana.edu";
       
        String startTime = PBS.showstart(pbsId);
        log.debug("check start time = " + startTime);
        return startTime;

    }
    
    public static String showstart(String pbsId) throws IOException, InterruptedException, PbsException {

        String startTime = "cannot determine start time for job";
       
        String pbsJobId = pbsId;
        
        Process p = Runtime.getRuntime().exec(new String[]{"bash","-c","showstart " + pbsJobId});
        p.waitFor();

        BufferedInputStream errStream = new BufferedInputStream(p.getErrorStream());

        if (errStream.available() > 0) {
            byte[] errdata = new byte[errStream.available()];
            errStream.read(errdata, 0, errStream.available());
            p.getOutputStream().close();
            p.getErrorStream().close();
            errStream.close();

            // If we got errors, means we can get the estimated starting time.
            // we will try next time but log the info and return the message 
            // as "can not get the starting time"
        } else {

            BufferedInputStream ef = new BufferedInputStream(p.getInputStream());
            byte[] data = new byte[ef.available()];
            ef.read(data, 0, ef.available());
            ef.close();
            p.getOutputStream().close();
            p.getErrorStream().close();
            String[] result = (new String(data)).split("\n");

            for (int i = 0; i < result.length; i++) {
                if (result[i].contains("based start in")) {
                    startTime = result[i];
                }
            }

        }

        return startTime;

    }

    public static String PbsResUsage(DrmJobRecord drmJobRecord, String resKey) throws IOException, InterruptedException, PbsException {

        final String drmJobId = drmJobRecord.getExtJobId();

        //String workDirPath = drmJobId.split("__")[0];
        //String gpId = drmJobId.split("__")[1];
        String pbsId = drmJobId.split("__")[0];
        String clusterName = drmJobId.split("__")[1];
        String pbsJobId = pbsId + "@" + clusterName;

        //String pbsJobId = jobID + "@m1.mason.indiana.edu";
        Process p = Runtime.getRuntime().exec("qstat -f " + pbsJobId);
        p.waitFor();

        BufferedInputStream errStream = new BufferedInputStream(p.getErrorStream());

        // If we got errors while pulling the detail job information
        // we just return null
        if (errStream.available() > 0) {
            byte[] errdata = new byte[errStream.available()];
            errStream.read(errdata, 0, errStream.available());
            p.getOutputStream().close();
            p.getErrorStream().close();
            errStream.close();

            return null;

        }
        // we successfully get the detail job real time information 
        else {
            BufferedInputStream ef = new BufferedInputStream(p.getInputStream());
            byte[] data = new byte[ef.available()];
            ef.read(data, 0, ef.available());
            ef.close();
            p.getOutputStream().close();
            p.getErrorStream().close();
            String Result = new String(data);
            
            // we need to parse the job real time information in order to get 
            // the value we want to return 
            String value = parseResKeyValue(resKey, Result.split("\n"));
            
            //log.debug("PbsResUsage: " + resKey + " = " + value);
            
            // somehow we can not parse the detail real time information 
            // we just simply return null 
            if (value == null || value.isEmpty()){
                return null;
            }

            else if (resKey.equals("cput") || resKey.equals("walltime")) {
                String[] info = value.split(":", 3);
                int tSec = Integer.parseInt(info[2]);
                int tMin = Integer.parseInt(info[1]);
                int tHur = Integer.parseInt(info[0]);
                Long rt = tHur * 60 * 60 + tMin * 60 + tSec * 1L;
                return rt.toString();
            } else if (resKey.equals("mem") || resKey.equals("vmem")) {
                String mem = value.substring(0, value.length() - 2);
                Long memValue = Long.parseLong(mem) * 1024L;
                return memValue.toString();

            } else if (resKey.equals("start_time") || resKey.equals("qtime")){
                //slog.error(new String("parse time: "+value));
                //String[] info = value.split(" ", 5);
                //String year = info[4];    
                //String month = info[1];
                //String date = info[2];
                //String hrs = info[3].split(":",3)[0];
                //String min = info[3].split(":",3)[1];
                //String sec = info[3].split(":",3)[2];
                return value;
            }
            
             else {
                return value;
            }

        }

    }

    
    
    public static String getKeyValue(String key, String[] info) {

        String[] line;
        String header = "";
        String value = "";

        for (int i = 0; i < info.length; i++) {
            if (info[i].contains(":")) {
                line = info[i].split(":", 2);
            } else {
                line = info[i].split("=", 2);
            }
            header = line[0].trim();
            //System.out.println("Header = " + header);
            value = line[1].trim();
            // System.out.println("value = " + value);

            if (key.equals(header)) {
                return value;
            }
        }

        return null;

    }
    
    public static String parseResKeyValue(String key, String[] info) {

        String[] line;
        String header = "";
        String value = "";

        for (int i = 0; i < info.length; i++) {
            if (info[i].contains("=")) {
                line = info[i].split("=", 2);
            } else {
                line = info[i].split(":", 2);
            }

            if (line.length > 1) {
                header = line[0].trim();
                //System.out.println("Header = " + header);
                value = line[1].trim();
                // System.out.println("value = " + value);

                if (header.contains(key)) {
                    return value;
                }
            }

        }

        return null;

    }

    public static Long getPbsFinalResUsage(String key, String[] info) {

        String[] line;
        String header = "";
        String value = "";

        for (int i = 0; i < info.length; i++) {
            //if (info[i].contains("=")) {
            //    line = info[i].split("=", 2);
            //} else {
            line = info[i].split("=", 2);
            //}
            header = line[0].trim();
            //System.out.println("Header = " + header);
            value = line[1].trim();
            // System.out.println("value = " + value);

            if (key.equals(header)) {
                if (key.equals("cput") || key.equals("walltime")) {
                    String[] data = value.split(":", 3);
                    int tSec = Integer.parseInt(data[2]);
                    int tMin = Integer.parseInt(data[1]);
                    int tHur = Integer.parseInt(data[0]);
                    Long rt = tHur * 60 * 60 + tMin * 60 + tSec * 1L;
                    return rt;
                } else if (key.equals("mem") || key.equals("vmem")) {
                    String mem = value.substring(0, value.length() - 2);
                    Long memValue = Long.parseLong(mem) * 1024L;
                    return memValue;

                }

            }
        }

        return null;

    }

    public static boolean qdel(String JobID) throws IOException, InterruptedException, PbsException {

        Process p = Runtime.getRuntime().exec("qdel " + JobID);
        p.waitFor();

        BufferedInputStream errStream = new BufferedInputStream(p.getErrorStream());

        // if we receive some error message while deleting the job, we
        // will need to report the message back to the job runner
        if (errStream.available() > 0) {
            byte[] errdata = new byte[errStream.available()];
            errStream.read(errdata, 0, errStream.available());
            p.getOutputStream().close();
            p.getErrorStream().close();
            errStream.close();
            throw new PbsException((new String(errdata)));
        }

        return true;
    }

    public static boolean hasErrorsInStdout(File stderr) throws IOException {

        String[] errorKeyWords = {"error", "errors", "abort", "aborted", "core dump", "exception"};

        Scanner scanner = new Scanner(stderr);

        //now read the stdout file line by line...
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();

            for (String errorKeyWord : errorKeyWords) {

                // If we find an error keyword, we will return true
                if (org.apache.commons.lang.StringUtils.containsIgnoreCase(line, errorKeyWord)) {
                    return true;
                }
            }
        }
        return false;
    }

}
