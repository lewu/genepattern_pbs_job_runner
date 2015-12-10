
package edu.iu.gp;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.jar.Attributes;

/**
 *
 * @author lewu@iu.edu
 */
public class CommandTemplate {

    private String command;
    private String workDir;
    private String comments;
    private String pbsFile;
    private String epiFile;
    private String header = "#!/bin/bash";
    

    public CommandTemplate(String workDir, String pbsCommand) throws IOException {
        this.command = pbsCommand;
        this.workDir = workDir;
    }

    public void setComment(String commnets) {
        this.comments = commnets;
    }

    public String getPbsFileName() {
        return pbsFile;
    }

    public String getEpiFileName() {
        return epiFile;
    }

    public void createExecutableFile() throws IOException {

        File pbsOutFile = new File(workDir, ".command.pbs");
        BufferedWriter pbsOut = new BufferedWriter(new FileWriter(pbsOutFile, true));

        pbsOut.write(header + "\n");

        if (comments != null) {
            pbsOut.write("# " + comments);
        }

        pbsOut.write(command + "\n");

        pbsOut.close();

        this.pbsFile = pbsOutFile.getAbsolutePath();
    }

    public void createEpilogueFile() throws IOException {
        
        
        File pbsDir = new File(workDir, ".pbs");

        // we need a special directory to store the epilogue.sh and epilogue.pbs
        //if the directory does not exist, create it
        if (!pbsDir.exists()) {
            pbsDir.mkdir();
        }

        File epiOutFile = new File(pbsDir, ".epilogue.sh");
        epiOutFile.setExecutable(true);
        epiOutFile.setWritable(true);

        BufferedWriter epiOut = new BufferedWriter(new FileWriter(epiOutFile, true));

        String epilog = epiOutFile.getAbsolutePath().replace("sh", "pbs");

        epiOut.write(header + "\n");

        String epiCommand = "echo \"Job ID:${1}\" >>" + epilog + "\n"
                + "echo \"User_ID:${2}\" >>" + epilog + "\n"
                + "echo \"Group_ID:${3}\">>" + epilog + "\n"
                + "echo \"Job_Name:${4}\">>" + epilog + "\n"
                + "echo \"Session_ID:${5}\">>" + epilog + "\n"
                + "echo \"Resource_List:${6}\">>" + epilog + "\n"
                + "echo \"Resources_Used:${7}\">>" + epilog + "\n"
                + "echo \"Queue_Name:${8}\">>" + epilog + "\n"
                + "echo \"Account_String:${9}\">>" + epilog + "\n"
                + "echo \"Job_Exit_Code:${10}\">>" + epilog + "\n"
                + "exit 0 \n";

        epiOut.write(epiCommand + "\n");

        epiOut.close();
  
        this.epiFile = epiOutFile.getAbsolutePath();

        
        
        Path epiFilePath = Paths.get(this.epiFile);
        
        setPermission(epiFilePath, PosixFilePermission.OWNER_EXECUTE);

    }

    
    private static void setPermission(Path path, PosixFilePermission permission)
            throws IOException {
        System.out.println("\nSetting permission for " + path.getFileName());
        PosixFileAttributeView view = Files.getFileAttributeView(path,
                PosixFileAttributeView.class);

        PosixFileAttributes attributes = view.readAttributes();

        Set<PosixFilePermission> permissions = attributes.permissions();
        permissions.add(permission);

        view.setPermissions(permissions);
        System.out.println();

    }

    
}
