package edu.iu.gp; 

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import org.apache.log4j.Logger;
import org.genepattern.drm.CpuTime;
import org.genepattern.drm.DrmJobRecord;
import org.genepattern.drm.DrmJobState;
import org.genepattern.drm.DrmJobStatus;
import org.genepattern.drm.JobRunner;
import org.genepattern.drm.DrmJobSubmission;
import org.genepattern.server.executor.CommandExecutorException;

/**
 * An implementation of the JobRunner interface for local execution.
 *
 * To use this, add the following to the config_.yaml file for your server.
 *
 * <pre>
 * LocalJobRunner:
 * classname: org.genepattern.server.executor.drm.JobExecutor
 * configuration.properties:
 * jobRunnerClassname: org.genepattern.drm.impl.local.LocalJobRunner
 * jobRunnerName: LocalJobRunner
 * lookupType: DB
 * #lookupType: HASHMAP
 * default.properties:
 * job.logFilename: .pbs.out
 * </pre>
 *
 * @author pcarr
 *
 */
/**
 *
 * implemented by lewu@iu.edu
 */
public class PbsJobRunner implements JobRunner {

    private static final Logger log = Logger.getLogger(PbsJobRunner.class);

    @Override
    public void stop() {
        log.info("Stopping PbsJobRunner");
    }

    @Override
    public String startJob(final DrmJobSubmission drmJobSubmission) throws CommandExecutorException {

        final String gpJobId = drmJobSubmission.getGpJobNo().toString();
        final String workDir = drmJobSubmission.getWorkingDir().getAbsolutePath();
        String pbsJobID, hostName = null;

        // Create a PBS job instance
        try {
            PbsJob pbsjob = new PbsJob(drmJobSubmission);

            /* 
             // we can still manually change the PBS setting after 
             // the pbsjob instance has been created 
             pbsjob.setWallTime("2:00:00");
             pbsjob.setQueue("batch");
             pbsjob.setNodes("1");
             pbsjob.setHostName("m1.mason.indiana.edu");
             pbsjob.setPpn("4");
             pbsjob.setVmem("64gb");
             */
            // create the pbs submission command string
            pbsjob.buildSubmissionScript();

            // submit the job
            pbsJobID = PBS.qsub(pbsjob.getPbsScript());

            //after the job completes, if the logfile param was set, write the command line to the logfile
            logCommandLine(drmJobSubmission);

            // After the job has been submitted to cluster.
            // we need to return the jobID for GP server to track
            // we will return jobdir_pbsid_hostname as the final job id 
            hostName = drmJobSubmission.getProperty("pbs.host");
            if (hostName != null) {
                //return workDir + "__" + gpJobId + "__" + pbsJobID + "__" + hostName;
                return pbsJobID + "__" + hostName;
            } else {
                // we must know the cluster name and build it into the jobid for 
                // further checking the job status. 
                throw new CommandExecutorException("can not find the job running host name"
                        + "from the config.yaml file");
            }

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            throw new CommandExecutorException(e.getMessage());
        } catch (InterruptedException e) {
            throw new CommandExecutorException(e.getMessage());
        } catch (PbsException e) {
            throw new CommandExecutorException(e.getMessage());
        }

    }

    @Override
    public DrmJobStatus getStatus(final DrmJobRecord drmJobRecord) {
        final String drmJobId = drmJobRecord.getExtJobId();
        DrmJobStatus drmJobStatus;

        // start to check the job status
        try {

            // we need to parse the gp id to dirid and pbs id
            //String workDirPath = drmJobId.split("__")[0];
            String workDirPath = drmJobRecord.getWorkingDir().toString();
            //String gpId = drmJobId.split("__")[1];
            String pbsId = drmJobId.split("__")[0];
            String clusterName = drmJobId.split("__")[1];
            String pbsJobStatus = PBS.qstat(drmJobRecord);
            //log.error(new String("job status is : " + pbsJobStatus));

            if (pbsJobStatus != null && !pbsJobStatus.isEmpty()) {
                if (pbsJobStatus.trim().compareToIgnoreCase("R") == 0) {
                    //log.error(new String("start to check realtime info"));
                    String cputInfo = null ;
                    cputInfo = PBS.PbsResUsage(drmJobRecord,"cput");
                    String vmemInfo = null;
                    vmemInfo = PBS.PbsResUsage(drmJobRecord,"vmem");
                    String startInfo = null ;
                    startInfo = PBS.PbsResUsage(drmJobRecord,"start_time");
                    String qtimeInfo = null;
                    qtimeInfo = PBS.PbsResUsage(drmJobRecord,"qtime");
                    //log.error(new String("finish to check realtime info"));
                    
                    DrmJobStatus.Builder b = new DrmJobStatus.Builder(drmJobId, DrmJobState.RUNNING);
                    
                    if (vmemInfo != null ){
                        b.memory(Long.parseLong(vmemInfo));
                    }
                    if (cputInfo != null ){
                        b.cpuTime(new CpuTime(Long.parseLong(cputInfo), TimeUnit.SECONDS));
                    }
                    if (startInfo !=null ){
                        //log.error(new String("get data back:" + startInfo));
                        SimpleDateFormat dt = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy");
                        Date sdate = dt.parse(startInfo);
                        b.startTime(sdate);
                    }
                    if (qtimeInfo !=null ){
                       
                        SimpleDateFormat dt = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy");
                        Date qdate = dt.parse(qtimeInfo);
                        b.submitTime(qdate);
                    }
                    drmJobStatus = b.build();
                } // We got the "C" status, that means job was finished. But we still need to 
                // check what is the actual exit code. 
                else if (pbsJobStatus.trim().compareToIgnoreCase("C") == 0) {

                    File stderr = drmJobRecord.getStderrFile();
                    File epilogueOut = new File(new File(workDirPath,".pbs"), ".epilogue.pbs");
                    //File epilogueOut = new File(workDirPath, ".epilogue.pbs");

                    // We will need to check whether the epilogue.pbs has 
                    // been written to job working directory.                
                    if (epilogueOut.exists()) {

                        String epiData = (new Scanner(epilogueOut)).useDelimiter("\\z").next();
                        String exitCode = PBS.getKeyValue("Job_Exit_Code", epiData.split("\n"));
                        String queueName = PBS.getKeyValue("Queue_Name", epiData.split("\n"));
                        String resourcesUsed = PBS.getKeyValue("Resources_Used", epiData.split("\n"));
                        // get the cpu time in second
                        Long cput = PBS.getPbsFinalResUsage("cput", resourcesUsed.split(","));
                        // get the walltime time in second
                        Long walltime = PBS.getPbsFinalResUsage("walltime", resourcesUsed.split(","));
                        // get the mem usage in bytes
                        Long mem = PBS.getPbsFinalResUsage("mem", resourcesUsed.split(","));
                        // get the vmem usage in bytes
                        Long vmem = PBS.getPbsFinalResUsage("vmem", resourcesUsed.split(","));

                        // If job finished successfully, we got exit_code = 0
                        if (exitCode.compareToIgnoreCase("0") == 0) {

                            // PBS script finshed successfully, but we need to do one more check on
                            // stderr file to see whether there are some error messages shown.
                            // We use some pre-defined key words for checking the erros.
                            //
                            // if stderr exists
                            if (stderr.exists()) {
                                if (PBS.hasErrorsInStdout(stderr)) {
                                    drmJobStatus = new DrmJobStatus.Builder(drmJobId, DrmJobState.FAILED).exitCode(-1).jobStatusMessage("job finished, stderr exists and contains some errors, return " + pbsJobStatus).build();
                                } else {
                                    // stderr does exit but does not contain "error" keyword
                                    //  we return done
                                    //drmJobStatus = new DrmJobStatus.Builder(drmJobId, DrmJobState.DONE).exitCode(0).endTime(new Date()).build();

                                    DrmJobStatus.Builder b = new DrmJobStatus.Builder(drmJobId, DrmJobState.DONE);
                                    b.exitCode(0).endTime(new Date());
                                   

                                    if (vmem != null) {
                                        b.memory(vmem);
                                    } else {
                                        log.error("error parsing vmem from epilogue.pbs");
                                    }

                                    if (cput != null) {
                                        b.cpuTime(new CpuTime(cput, TimeUnit.SECONDS));
                                    } else {
                                        log.error("error parsing cput from epilogue.pbs");
                                    }
                                    
                                    b.jobStatusMessage("job finished, stderr exists but no errors in it, everything looks good, return " + pbsJobStatus);
                                    
                                    drmJobStatus = b.build();

                                }
                            } // stderr does not exist, it means everything looking good. 
                            else {
                                //drmJobStatus = new DrmJobStatus.Builder(drmJobId, DrmJobState.DONE).exitCode(0).endTime(new Date()).build();
                                DrmJobStatus.Builder b = new DrmJobStatus.Builder(drmJobId, DrmJobState.DONE);
                                b.exitCode(0).endTime(new Date());

                                if (vmem != null) {
                                    b.memory(vmem);
                                } else {
                                    log.error("error parsing vmem from epilogue.pbs");
                                }

                                if (cput != null) {
                                    b.cpuTime(new CpuTime(cput, TimeUnit.SECONDS));
                                } else {
                                    log.error("error parsing cput from epilogue.pbs");
                                }

                                b.jobStatusMessage("job finished, everything looks good, return " + pbsJobStatus);
                                
                                drmJobStatus = b.build();
                            }
                        } // we got non-zero job exit code or we got null 
                        else {
                            drmJobStatus = new DrmJobStatus.Builder(drmJobId, DrmJobState.FAILED).exitCode(-1).jobStatusMessage("job finished but exit code is not 0, return " + pbsJobStatus).build();
                        }
 
                        // finish checking the epilogue file, we need to delete this file and the original epilogue.sh file
                        try {

                            File epilogueSh = new File(workDirPath, "epilogue.sh");
                            File commandPBS = new File(workDirPath, "command.pbs");


                            //if (epilogueSh.delete()) {
                            //    log.debug(epilogueSh.getName() + " is deleted!");
                            //} else {
                            //    log.error("Epilogue sh file delete operation is failed.");
                            //}

                            //if (commandPBS.delete()) {
                            //    log.debug(commandPBS.getName() + " is deleted!");
                            //} else {
                            //    log.error("Command pbs file delete operation is failed.");
                            //s} 
                        } catch (Exception e) {
                            log.error(e);
                        }

                    } else {

                        // If we can not find both stdout and epilogue.pbs file, 
                        // then there must be something wrong. 
                        drmJobStatus = new DrmJobStatus.Builder(drmJobId, DrmJobState.FAILED).exitCode(-1).jobStatusMessage("can not find both pbs epilogue outputs and stderr, something must be wrong, return " + pbsJobStatus).build();
                    }

                } else if (pbsJobStatus.trim().compareToIgnoreCase("Q") == 0) {
                    // job is queued, we want to know when job will start
                    log.debug("we need to check the showstart time");
                    String startTime = PBS.showstart(drmJobRecord);
                    
                    drmJobStatus = new DrmJobStatus.Builder(drmJobId, DrmJobState.QUEUED).jobStatusMessage(startTime).build();
                } else if (pbsJobStatus.trim().compareToIgnoreCase("E") == 0) {
                    drmJobStatus = new DrmJobStatus.Builder(drmJobId, DrmJobState.RUNNING).build();
                } 
                else if (pbsJobStatus.trim().compareToIgnoreCase("H") == 0) {
                    drmJobStatus = new DrmJobStatus.Builder(drmJobId, DrmJobState.QUEUED_HELD).build();
                } else if (pbsJobStatus.trim().compareToIgnoreCase("F") == 0) {
                    drmJobStatus = new DrmJobStatus.Builder(drmJobId, DrmJobState.FAILED).exitCode(-1).jobStatusMessage("job didn't finish normallyb, either stderr contains error or epilogue exit code is not 0, return " + pbsJobStatus).build();
                } else if (pbsJobStatus.trim().compareToIgnoreCase("S") == 0) {
                    drmJobStatus = new DrmJobStatus.Builder(drmJobId, DrmJobState.SUSPENDED).build();
                } else {
                    // we got a strange status code that we don't have the definitation  
                    // we make this job as failed
                    log.debug("getStatus, drmJobId=" + drmJobId + "; Unknow ");
                    drmJobStatus = new DrmJobStatus.Builder(drmJobId, DrmJobState.FAILED).exitCode(-1).jobStatusMessage("receive strange code from PBS , return " + pbsJobStatus).build();
                    //drmJobStatus = new DrmJobStatus.Builder(drmJobId, DrmJobState.UNDETERMINED).build();
                }

                //log.debug("getStatus, drmJobId=" + drmJobId + "; status is: " + pbsJobStatus);

            } else {// we got null as the job status because we can not find the information 
                // of "job_state" from the "qstat -f" commnad
                // we make this job as failed
                log.debug("getStatus, drmJobId=" + drmJobId + "; get null status ");
                drmJobStatus = new DrmJobStatus.Builder(drmJobId, DrmJobState.FAILED).exitCode(-1).jobStatusMessage("receive null from PBS, return " + pbsJobStatus).build();
            }

            return drmJobStatus;

        } catch (Exception e) {

            // we receive an Exception while checking the job status
            // it is very likely that this job has been finished long time ago
            // we make this job as failed
            log.error(e);
            Thread.currentThread().interrupt();
            return new DrmJobStatus.Builder(drmJobId, DrmJobState.FAILED).exitCode(-1).jobStatusMessage("got exception while chceking job status. " + e.getMessage()).build();
        }

    }

    @Override
    public boolean cancelJob(DrmJobRecord drmJobRecord) throws Exception {
        //public boolean cancelJob(final String drmJobId, final DrmJobSubmission drmJobSubmission) throws CommandExecutorException {

        final String drmJobId = drmJobRecord.getExtJobId();
        try {

            //String workDirPath = drmJobId.split("__")[0];
            //String gpId = drmJobId.split("__")[1];
            String pbsId = drmJobId.split("__")[0];
            String clusterName = drmJobId.split("__")[1];
            boolean delStatus = PBS.qdel(pbsId + "@" + clusterName);

            return delStatus;

        } catch (IOException e) {
            throw new CommandExecutorException("Can not cancel job: " + drmJobId.split("_")[0]
                    + " ERROR: " + e.getMessage());
        } catch (InterruptedException e) {
            throw new CommandExecutorException("Can not cancel job: " + drmJobId.split("_")[0]
                    + " ERROR: " + e.getMessage());
        } catch (PbsException e) {
            throw new CommandExecutorException("Can not cancel job: " + drmJobId.split("_")[0]
                    + " ERROR: " + e.getMessage());
        }

    }

    /**
     * Helper function to output some messages for debugging
     *
     * @param message
     * @param toFile
     */
    private static void writeToFile(final String message, final File toFile) {
        toFile.getParentFile().mkdirs();
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(toFile));
            writer.write(message);
        } catch (IOException e) {
            log.error("Error writing file=" + toFile, e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * If configured by the server admin, write the command line into a log file
     * in the working directory for the job.
     * <pre>
     *     # flag, if true save the command line into a log file in the working directory for each job
     * rte.save.logfile: false
     * # the name of the command line log file
     * rte.logfile: .rte.out
     * </pre>
     *
     * @author pcarr
     */
    private void logCommandLine(final DrmJobSubmission drmJobSubmission) {
        if (drmJobSubmission.getLogFile() == null) {
            // a null logfile means "don't write the log file"
            return;
        }

        final File commandLogFile;
        if (!drmJobSubmission.getLogFile().isAbsolute()) {
            //relative path is relative to the working directory for the job
            commandLogFile = new File(drmJobSubmission.getWorkingDir(), drmJobSubmission.getLogFile().getPath());
        } else {
            commandLogFile = drmJobSubmission.getLogFile();
        }

        log.debug("saving command line to log file ...");
        String commandLineStr = "";
        boolean first = true;
        for (final String arg : drmJobSubmission.getCommandLine()) {
            if (first) {
                commandLineStr = arg;
                first = false;
            } else {
                commandLineStr += (" " + arg);
            }
        }

        if (commandLogFile.exists()) {
            log.error("log file already exists: " + commandLogFile.getAbsolutePath());
            return;
        }

        BufferedWriter bw = null;
        try {
            FileWriter fw = new FileWriter(commandLogFile);
            bw = new BufferedWriter(fw);
            bw.write(commandLineStr);
            bw.newLine();
            int i = 0;
            for (final String arg : drmJobSubmission.getCommandLine()) {
                bw.write("    arg[" + i + "]: '" + arg + "'");
                bw.newLine();
                ++i;
            }
            bw.close();
        } catch (IOException e) {
            log.error("error writing log file: " + commandLogFile.getAbsolutePath(), e);
            return;
        } catch (Throwable t) {
            log.error("error writing log file: " + commandLogFile.getAbsolutePath(), t);
            log.error(t);
        } finally {
            if (bw != null) {
                try {
                    bw.close();
                } catch (IOException e) {
                    log.error(e);
                }
            }
        }
    }

}
