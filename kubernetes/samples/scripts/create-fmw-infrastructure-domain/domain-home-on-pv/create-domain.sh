#!/usr/bin/env bash
# Copyright (c) 2018, 2021, Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
#
# Description
#  This sample script creates a Fusion Middleware Infrastructure domain home on an existing PV/PVC,
#  and generates the domain resource yaml file, which can be used to restart the Kubernetes artifacts
#  of the corresponding domain.
#
#  The domain creation inputs can be customized by editing create-domain-inputs.yaml
#
#  The following pre-requisites must be handled prior to running this script:
#    * The kubernetes namespace must already be created
#    * The kubernetes secrets 'username' and 'password' of the admin account have been created in the namespace
#    * The host directory that will be used as the persistent volume must already exist
#      and have the appropriate file permissions set.
#    * The kubernetes persistent volume must already be created
#    * The kubernetes persistent volume claim must already be created
#

# Initialize
script="${BASH_SOURCE[0]}"
scriptDir="$( cd "$( dirname "${script}" )" && pwd )"
source ${scriptDir}/../../common/utility.sh
source ${scriptDir}/../../common/validate.sh

function usage {
  echo usage: ${script} -o dir -i file [-e] [-v] [-h]
  echo "  -i Parameter inputs file, must be specified."
  echo "  -o Output directory for the generated yaml files, must be specified."
  echo "  -e Also create the resources in the generated yaml files, optional."
  echo "  -v Validate the existence of persistentVolumeClaim, optional."
  echo "  -h Help"
  exit $1
}

#
# Parse the command line options
#
doValidation=false
executeIt=false
while getopts "evhi:o:" opt; do
  case $opt in
    i) valuesInputFile="${OPTARG}"
    ;;
    o) outputDir="${OPTARG}"
    ;;
    v) doValidation=true
    ;;
    e) executeIt=true
    ;;
    h) usage 0
    ;;
    *) usage 1
    ;;
  esac
done

if [ -z ${valuesInputFile} ]; then
  echo "${script}: -i must be specified."
  missingRequiredOption="true"
fi

if [ -z ${outputDir} ]; then
  echo "${script}: -o must be specified."
  missingRequiredOption="true"
fi

if [ "${missingRequiredOption}" == "true" ]; then
  usage 1
fi

#
# Function to initialize and validate the output directory
# for the generated yaml files for this domain.
#
function initOutputDir {
  domainOutputDir="${outputDir}/weblogic-domains/${domainUID}"
  # Create a directory for this domain's output files
  mkdir -p ${domainOutputDir}

  removeFileIfExists ${domainOutputDir}/${valuesInputFile}
  removeFileIfExists ${domainOutputDir}/create-domain-inputs.yaml
  removeFileIfExists ${domainOutputDir}/create-domain-job.yaml
  removeFileIfExists ${domainOutputDir}/delete-domain-job.yaml
  removeFileIfExists ${domainOutputDir}/domain.yaml
}

#
# Function to setup the environment to run the create domain job
#
function initialize {

  # Validate the required files exist
  validateErrors=false

  validateKubectlAvailable

  if [ -z "${valuesInputFile}" ]; then
    validationError "You must use the -i option to specify the name of the inputs parameter file (a modified copy of kubernetes/samples/scripts/create-weblogic-domain/domain-home-on-pv/create-domain-inputs.yaml)."
  else
    if [ ! -f ${valuesInputFile} ]; then
      validationError "Unable to locate the input parameters file ${valuesInputFile}"
    fi
  fi

  if [ -z "${outputDir}" ]; then
    validationError "You must use the -o option to specify the name of an existing directory to store the generated yaml files in."
  fi

  createJobInput="${scriptDir}/create-domain-job-template.yaml"
  if [ ! -f ${createJobInput} ]; then
    validationError "The template file ${createJobInput} for creating a WebLogic domain was not found"
  fi

  deleteJobInput="${scriptDir}/delete-domain-job-template.yaml"
  if [ ! -f ${deleteJobInput} ]; then
    validationError "The template file ${deleteJobInput} for deleting a WebLogic domain was not found"
  fi

  dcrInput="${scriptDir}/../../common/jrf-domain-template.yaml"
  if [ ! -f ${dcrInput} ]; then
    validationError "The template file ${dcrInput} for creating the domain resource was not found"
  fi

  failIfValidationErrors

  validateCommonInputs

  initOutputDir
  getKubernetesClusterIP
  if [ -z "${t3PublicAddress}" ]; then
    t3PublicAddress="${K8S_IP}"
  fi

  if [ "${fmwDomainType}" == "JRF" -a "${createDomainFilesDir}" == "wlst" ]; 
  then
   rm -rf ${scriptDir}/common/createFMWDomain.py
   cp -f ${scriptDir}/../../common/createFMWJRFDomain.py \
         ${scriptDir}/common/createFMWDomain.py
  fi
  if [ "${fmwDomainType}" == "RestrictedJRF" -a "${createDomainFilesDir}" == "wlst" ]; 
  then
    rm -rf ${scriptDir}/common/createFMWDomain.py
    cp -f ${scriptDir}/../../common/createFMWRestrictedJRFDomain.py \
         ${scriptDir}/common/createFMWDomain.py
  fi
  domain_type=${fmwDomainType}
}

# create domain configmap using what is in the createDomainFilesDir
function createDomainConfigmap {
  # Use the default files if createDomainFilesDir is not specified
  if [ -z "${createDomainFilesDir}" ]; then
    createDomainFilesDir=${scriptDir}/wlst
  elif [[ ! ${createDomainFilesDir} == /* ]]; then
    createDomainFilesDir=${scriptDir}/${createDomainFilesDir}
  fi

  # customize the files with domain information
  externalFilesTmpDir=$domainOutputDir/tmp
  mkdir -p $externalFilesTmpDir
  cp ${createDomainFilesDir}/* ${externalFilesTmpDir}/
  if [ -d "${scriptDir}/common" ]; then
    cp ${scriptDir}/common/* ${externalFilesTmpDir}/
  fi
  if [ -d "${scriptDir}/../../common" ]; then
    cp ${scriptDir}/../../common/wdt-and-wit-utility.sh ${externalFilesTmpDir}/
  fi

  cp ${domainOutputDir}/create-domain-inputs.yaml ${externalFilesTmpDir}/
 
  # Set the domainName in the inputs file that is contained in the configmap.
  # this inputs file can be used by the scripts, such as WDT, that creates the WebLogic
  # domain in the job.
  echo domainName: $domainName >> ${externalFilesTmpDir}/create-domain-inputs.yaml

  if [ -f ${externalFilesTmpDir}/prepare.sh ]; then
   bash ${externalFilesTmpDir}/prepare.sh -i ${externalFilesTmpDir} -t ${fmwDomainType}
  fi

  # Now that we have the model file in the domainoutputdir/tmp,
  # we can add the kubernetes section to the model file.
  updateModelFile
 
  # create the configmap and label it properly
  local cmName=${domainUID}-create-fmw-infra-sample-domain-job-cm
  kubectl create configmap ${cmName} -n $namespace --from-file $externalFilesTmpDir --dry-run -o yaml | kubectl apply -f -

  echo Checking the configmap $cmName was created
  local num=`kubectl get cm -n $namespace | grep ${cmName} | wc | awk ' { print $1; } '`
  if [ "$num" != "1" ]; then
    fail "The configmap ${cmName} was not created"
  fi

  kubectl label configmap ${cmName} -n $namespace weblogic.domainUID=$domainUID weblogic.domainName=$domainName

  rm -rf $externalFilesTmpDir
}

#
# Function to run the job that creates the domain
#
function createDomainHome {

  # create the config map for the job
  createDomainConfigmap

  # There is no way to re-run a kubernetes job, so first delete any prior job
  CONTAINER_NAME="create-fmw-infra-sample-domain-job"
  JOB_NAME="${domainUID}-${CONTAINER_NAME}"
  deleteK8sObj job $JOB_NAME ${createJobOutput}

  echo Creating the domain by creating the job ${createJobOutput}
  kubectl create -f ${createJobOutput}

  # When using WDT to create a domain, a job to create a domain is started. It then creates
  # a pod which will run a script that creates a domain. The script then will extract the domain
  # resource using  WDT's extractDomainResource tool. So, the following code loops until the
  # domain resource is created by exec'ing into the pod to look for the presence of domainCreate.yaml file.

  if [ "$useWdt" = true ]; then
    POD_NAME=`kubectl get pods -n ${namespace} | grep ${JOB_NAME} | awk ' { print $1; } '`
    echo "Waiting for results to be available from $POD_NAME"
    kubectl wait --timeout=600s --for=condition=ContainersReady pod $POD_NAME
    #echo "Fetching results"
    sleep 30
    max=30
    count=0
    kubectl exec $POD_NAME -c create-fmw-infra-sample-domain-job -n ${namespace} -- bash -c "ls -l ${domainPVMountPath}/wdt"
    kubectl exec $POD_NAME -c create-fmw-infra-sample-domain-job -n ${namespace} -- bash -c "ls -l ${domainPVMountPath}/wdt" | grep "domaincreate.yaml"
    while [ $? -eq 1 -a $count -lt $max ]; do
      sleep 5
      kubectl exec $POD_NAME -c create-fmw-infra-sample-domain-job -n ${namespace} -- bash -c "ls -l ${domainPVMountPath}/wdt"
      count=`expr $count + 1`
      kubectl exec $POD_NAME -c create-fmw-infra-sample-domain-job -n ${namespace} -- bash -c "ls -l ${domainPVMountPath}/wdt" | grep "domaincreate.yaml"
    done
    kubectl cp ${namespace}/$POD_NAME:${domainPVMountPath}/wdt/domaincreate.yaml ${domainOutputDir}/domain.yaml

    # The pod waits for this script to copy the domain resource yaml (domainCreate.yaml) out of the pod and into
    # the output directory as domain.yaml. To let the pod know that domainCreate.yaml has been copied, a file called
    # doneExtract is copied into the pod. When the script running in the pod sees the doneExtract file, it exits.

    touch doneExtract
    kubectl cp doneExtract ${namespace}/$POD_NAME:${domainPVMountPath}/wdt/
    rm -rf doneExtract
  fi

  echo "Waiting for the job to complete..."
  JOB_STATUS="0"
  max=20
  count=0
  while [ "$JOB_STATUS" != "Completed" -a $count -lt $max ] ; do
    sleep 30
    count=`expr $count + 1`
    JOBS=`kubectl get pods -n ${namespace} | grep ${JOB_NAME}`
    JOB_ERRORS=`kubectl logs jobs/$JOB_NAME $CONTAINER_NAME -n ${namespace} | grep "ERROR:" `
    JOB_STATUS=`echo $JOBS | awk ' { print $3; } '`
    JOB_INFO=`echo $JOBS | awk ' { print "pod", $1, "status is", $3; } '`
    echo "status on iteration $count of $max"
    echo "$JOB_INFO"

    # Terminate the retry loop when a fatal error has already occurred.  Search for "ERROR:" in the job log file
    if [ "$JOB_STATUS" != "Completed" ]; then
      ERR_COUNT=`echo $JOB_ERRORS | grep "ERROR:" | wc | awk ' {print $1; }'`
      if [ "$ERR_COUNT" != "0" ]; then
        echo "A failure was detected in the log file for job $JOB_NAME."
        echo "$JOB_ERRORS"
        echo "Check the log output for additional information."
        fail "Exiting due to failure - the job has failed!"
      fi
    fi
  done

  # Confirm the job pod is status completed
  if [ "$JOB_STATUS" != "Completed" ]; then
    echo "The create domain job is not showing status completed after waiting 300 seconds."
    echo "Check the log output for errors."
    kubectl logs jobs/$JOB_NAME $CONTAINER_NAME -n ${namespace}
    fail "Exiting due to failure - the job status is not Completed!"
  fi

  # Check for successful completion in log file
  JOB_POD=`kubectl get pods -n ${namespace} | grep ${JOB_NAME} | awk ' { print $1; } '`
  JOB_STS=`kubectl logs $JOB_POD $CONTAINER_NAME -n ${namespace} | grep "Successfully Completed" | awk ' { print $1; } '`
  if [ "${JOB_STS}" != "Successfully" ]; then
    echo The log file for the create domain job does not contain a successful completion status
    echo Check the log output for errors
    kubectl logs $JOB_POD $CONTAINER_NAME -n ${namespace}
    fail "Exiting due to failure - the job log file does not contain a successful completion status!"
  fi
}

#
# Function to output to the console a summary of the work completed
#
function printSummary {

  # Get the IP address of the kubernetes cluster (into K8S_IP)
  getKubernetesClusterIP

  echo ""
  echo "Domain ${domainName} was created and will be started by the WebLogic Kubernetes Operator"
  echo ""
  if [ "${exposeAdminNodePort}" = true ]; then
    echo "Administration console access is available at http://${K8S_IP}:${adminNodePort}/console"
  fi
  if [ "${exposeAdminT3Channel}" = true ]; then
    echo "T3 access is available at t3://${K8S_IP}:${t3ChannelPort}"
  fi
  echo "The following files were generated:"
  echo "  ${domainOutputDir}/create-domain-inputs.yaml"
  echo "  ${createJobOutput}"
  echo "  ${dcrOutput}"
  echo ""
  echo "Completed"
}

# Perform the sequence of steps to create a domain
createDomain false
