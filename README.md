PhysiCloud
==========

This repository houses the code for PhysiCloud, a platform for programming and managing cyber-physical systems (CPS). This platform aims to provide platform-as-a-service (PaaS) capabilities to low-power, mobile CPS. Details about PhysiCloud's motivation and design philosophy are presented in [1].

The current release of the software provides programming abstractions for what we call cyber-physical resources: sensors, actuators, and computational results. All resources are modeled as channels, much like the resource model in the asynchronous distributed pi-calculus [2].

Developers implement controllers in Clojure and deploy each within a PhysiCloud task. PhysiCloud automatically handles the resource dependencies, whether they are internal or networked. Our framework leverages Clojure libraries to enable our abstractions, such as our own [watershed](https://github.com/hypower-org/watershed) and [aleph](http://github.com/ztellman/aleph).

PhysiCloud is tested on Linux and OSX based systems with the Java 7 JVM installed. It is also tested on the following embedded systems when a AOT compilation is run on the code:
* Beaglebone Black
* Raspberry Pi B+
* Udoo

For an example of how to construct a PhysiCloud instance, check out the [introductory example](https://github.com/hypower-org/physicloud/blob/master/test/physicloud/introductory_example.clj) source code.

To include PhysiCloud into your clojure project, use the following:

    [hypower-org/physicloud "0.1.1"]

---
### References

   [1] P. Glotfelter, T. Eichelberger, and P. Martin.  PhysiCloud: A Cloud-Computing Framework for Programming Cyber-Physical Systems. Multi-Conference on Systems and Control, 2014.
   
   [2] M. Hennessy, *A Distributed Pi-Calculus*. Cambridge University Press, 2007.
   
---
This work was funded by the National Science Foundation through grant CNS-1239221.
