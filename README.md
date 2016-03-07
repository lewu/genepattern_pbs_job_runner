**Requirements:**

(1) A shared file system between the GenePattern server and the computing cluster nodes.

(2) The GenePattern server hosting machine and the computing cluster nodes should have the 
    same architecture, so that all the GenePattern modules can be execuated on the computing
    cluster.
    
(3) The GenePattern server hosting machine must be a job submission node of the computing 
    cluster.
    
(4) The GenePattern server owner should have the ability to submit jobs to the computing 
    cluster with the same username. 
    
(5) TORQUE libraries should be installed on the GenePattern server hosting machine and the
    absoulate path to its execuatable binaries should be added into PATH variabe. 
    
(6) MOAB libraries should be installed on the GenePattern server hosting machine and the
    absoulate path to its execuatable binaries should be added into PATH variabe. In addition,
    the moab cllient server key pairs shoule be properly set up.
