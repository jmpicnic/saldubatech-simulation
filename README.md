# saldubatech-simulation
Simulation of Material Handling systems

# To build
## Preconditions
* Java 11 or later: Depending on local configuration, ensure that javac and java 
are in the path and that associated JAVA_HOME etc... are properly configured
* sbt 1.3+: `brew install sbt` (for macs)

## Build
* Clone from git: `git clone https://github.com/jmpicnic/saldubatech-simulation.git`
* Go to the top directory: `cd saldubatech-simulation`
* build and test with sbt: `sbt test`

## In IntelliJ

### Required PlugIn:
* Scala

### Recommended PlugIns:
* Database Navigator
* GitToolBox
* Grep Console
* HOCON
* PlantUML Integration
* PlantUML Syntax Check
  
### Other plugIns (for future)  
  JS GraphQL
  Swagger

To use IntelliJ, clone using git command line and import the sources using the sbt structure import.


## Circle CI
[![jmpicnic](https://circleci.com/gh/circleci/circleci-docs.svg?style=svg)](https://circleci.com/gh/jmpicnic/saldubatech-simulation)

## CodeCov
[![codecov](https://codecov.io/gh/jmpicnic/saldubatech-simulation/branch/master/graph/badge.svg?token=8CcUQaV1RQ)](https://codecov.io/gh/jmpicnic/saldubatech-simulation)
