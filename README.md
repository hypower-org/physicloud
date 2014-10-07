PhysiCloud
==========

This repository houses the code for PhysiCloud, a platform for programming and managing cyber-physical systems (CPS). This platform aims to provide platform-as-a-service (PaaS) capabilities to low-power, mobile CPS. Details about PhysiCloud's motivation and design philosophy are presented in [1].

The current release of the software provides programming abstractions for what we call cyber-physical resources: sensors, actuators, and computational results. All resources are modeled as channels, much like the resource model in the asynchronous distributed pi-calculus [2].

Developers can implement controllers in Clojure and deploy each within a PhysiCloud task. PhysiCloud automatically handles the resource dependencies, whether they are internal or networked. Our framework leverages some great Clojure libraries to enable our abstractions, such as [lamina](http://github.com/ztellman/lamina) and [aleph](http://github.com/ztellman/aleph).

PhysiCloud is should be able to run on any system that has the most recent versions of Clojure and Java 7. We have tested it on the [Udoo](http://www.udoo.org) as well as laptops. We hope to test it on Raspberry Pis and BeagleBone Blacks as soon as we get them in our hands!

Tutorials will be coming soon! For now, check out our test code for an example.

---
### References

   [1] P. Glotfelter, T. Eichelberger, and P. Martin.  PhysiCloud: A Cloud-Computing Framework for Programming Cyber-Physical Systems. Multi-Conference on Systems and Control, 2014.
   
   [2] M. Hennessy, *A Distributed Pi-Calculus*. Cambridge University Press, 2007.
   
---
This work was funded by the National Science Foundation through grant CNS-1239221.
