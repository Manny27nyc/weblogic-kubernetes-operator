# Copyright (c) 2017, 2021, Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

# This is called by the preStop hook script (stopServer.sh) that is specified
# by the WL operator for WL pod resource.  Before killing a pod, Kubernetes calls
# the preStop hook script.  Kubernetes will then kill the pod once the script returns
# or when the grace period timeout expires(see Operator shutdown.timeoutSeconds)
#
# This script shuts down the local server running in the container (admin or managed).
# There are 2 main scenarios, a domain with a Coherence cluster and one without.
# If there is no Coherence cluster, then the code will attempt to connect to the
# local server and do a shutdown.  If that doesn't work it will attempt to shutdown
# using NodeManager.  If that fails it just exits and kubernetes will kill the pod.
# If a Coherence cluster exists, then we must wait for the Coherence services to be
# safe before the server is shutdown, or we risk losing data.  This requires a
# a connection to the admin server and inspection of the Coherence MBeans.  The code
# will not shutdown the server until Coherence is safe.  If Coherence never becomes safe,
# then, eventually, the grace period will expire and Kubernetes will kill the pod.
#
# It users key and data files that were generated by the introspector
# for its nmConnect credentials.
#

import sys
import os
import traceback
import base64
import time as systime
import re

# Get an ENV var
def getEnvVar(var):
  val=os.environ.get(var)
  if val==None:
    print "ERROR: Env var ",var, " not set."
    sys.exit(1)
  return val


# Connect to Node Manager and shut down the local server
#
def shutdownUsingNodeManager(domainName, domainDir):
  try:
    print('Shutdown: Attempting shutdown using NodeManager')
    nmConnect(userConfigFile='/weblogic-operator/introspector/userConfigNodeManager.secure',
              userKeyFile='/tmp/userKeyNodeManager.secure.bin',
              host='127.0.0.1',port='5556',
              domainName=domainName,
              domainDir=domainDir,
              nmType='plain')
    print('Shutdown: Successfully connected to NodeManager')
    nmKill(server_name)
    print('Shutdown: Successfully killed server using NodeManager')
  except Exception, e:
    traceback.print_exc(file=sys.stdout)
    print('Shutdown: Failed to kill server using NodeManager')
    raise


# Return True if Coherence exists
def doesCoherenceExist():
  try:
    f = open(domain_path+'/config/config.xml', 'r')
    configData = f.read()
    f.close()
    return checkCoherenceClusterExist(configData)
  except:
    print('Shutdown: Exception reading config.xml, assume Coherence exists')
    return True


# Return True if there is a CoherenceClusterSystemResource. This will indicate that the domain is
# using Coherence
def checkCoherenceClusterExist(configData):
  try:
    # remove all whitespace include CR
    spacelessData = ''.join(configData.split())

    ELEMENT_NAME =  "coherence-cluster-system-resource"
    x =  re.search('<coherence-cluster-system-resource>[\s\S]*?<\/coherence-cluster-system-resource>',spacelessData)
    if (x is None):
      return False
    else:
      first, last = x.span()
      # check if the element value is empty (add 5 for the XML slash and 4 angle brackets)
      return (last-first-5) > (2 * len(ELEMENT_NAME))
  except:
    traceback.print_exc(file=sys.stdout)
    print('Shutdown: Exception processing config data, assume Coherence exists')
    return True


# If there is a Coherence cluster then we wait until it is safe to shutdown
# all distributed services.  Each distributed cache (with backup count > 0) has data
# that is in both in a primary partition on one cluster node (i.e. WLS server) and
# a backup partition on a different node.
# During rolling restart, Coherence rebalances the cluster and moves both primary and
# backup partitions across the nodes.  During this time the distributed services affected
# are consider ENDANGERED.  For example, in a 3 node cluster, if node 1 was being restarted
# and we shut down node 2 before Coherence was safe then we may lose data.
# NOTE: The overall timeout used to control the termination of this container is
# controlled by the Kubernetes graceful timeout value. So even
# if this code looped forever, Kubernetes will kill the pod once the timeout expires.
# The user must set that timeout to a large enough value to give Coherence time to get safe.
def waitUntilCoherenceSafe():
  print ('Shutdown: getting all service Coherence MBeans')
  query='Coherence:type=PartitionAssignment,service=*,*'

  # By default, Coherence will use a single WebLogic Runtime MBean server to managed
  # its MBeans.  That server will correspond to the current Coherence senior member,
  # which means that the Coherence MBeans will migrate to the oldest cluster member
  # during rolling restart.  By using the DomainRuntime MBean server (in admin server)
  # we can get access to the Coherence MBeans, regardless of the local Runtime MBean
  # server being used, since the DomainRuntime server is federated and will call the
  # individual MBean servers to get the MBeans throughout the domain.
  domainRuntime()

  # Wait forever until we get positive ack that it is ok to shutdown this server.
  done = False
  warnSleep = True
  while (not done):
    try:
      beans = list(mbs.queryMBeans(ObjectName(query), None))
      if beans is None or len(beans) == 0:
        # during rolling restart the beans might not be available right away
        # we need to wait since we know Coherence is enabled
        if warnSleep:
          print('Shutdown: Waiting until Coherence MBeans available... ')
          warnSleep = False
        systime.sleep(1)
        continue

      # Loop waiting for each service to be safe
      print('Shutdown: Coherence service bean count ' + str(len(beans)))
      for serviceBean in beans:
        objectName = serviceBean.getObjectName()
        waitUntilServiceSafeToShutdown(objectName)
      done = True
      print ('Shutdown: It is safe to shutdown Coherence')

    except:
      print ("Shutdown: Exception checking a service Coherence HAStatus, retrying...")
      traceback.print_exc(file=sys.stdout)
      dumpStack()
      systime.sleep(10)
      pass


# Wait until the specified Coherence service is safe to shutdown.
# If the cluster is a single node cluster then the service will always
# be ENDANGERED, therefore it is the responsibility of the user to
# set Coherence backup count to 0, or to set the terminate grace period
# to a low number since this method will just wait until the kubernetes kills the
# pod.
def waitUntilServiceSafeToShutdown(objectName):

  print ("Shutdown: checking Coherence service " + str(objectName))

  # NOTE: break loop when it safe to shutdown else stay in loop forever
  while (True):
    try:
      status = mbs.getAttribute(objectName,"HAStatus")
      if (status is None):
        print ("Shutdown: None returned for Coherence HAStatus")
        break

      print ('Shutdown: Coherence HAStatus is ' + status)
      if status != "ENDANGERED":
        break

      # Coherence caches are ENDANGERED meaning that we may lose data
      print ('Shutdown: Waiting until it is safe to shutdown Coherence server ...')
      systime.sleep(5)

    except:
      print ('Shutdown: An exception occurred getting Coherence MBeans, staying in loop checking for safe')
      traceback.print_exc(file=sys.stdout)
      dumpStack()
      systime.sleep(10)
      pass


#----------------------------------
# Main script
#----------------------------------
print ("Shutdown: main script")
domain_uid = getEnvVar('DOMAIN_UID')
admin_name = getEnvVar('ADMIN_NAME')
server_name = getEnvVar('SERVER_NAME')
domain_name = getEnvVar('DOMAIN_NAME')
domain_path = getEnvVar('DOMAIN_HOME')
service_name = getEnvVar('SERVICE_NAME')
local_admin_port = getEnvVar('SHUTDOWN_PORT_ARG')
local_admin_protocol = getEnvVar('SHUTDOWN_PROTOCOL_ARG')
timeout = getEnvVar('SHUTDOWN_TIMEOUT_ARG')
ignore_sessions = getEnvVar('SHUTDOWN_IGNORE_SESSIONS_ARG')
wait_for_all_sessions = getEnvVar('SHUTDOWN_WAIT_FOR_ALL_SESSIONS_ARG')
shutdown_type = getEnvVar('SHUTDOWN_TYPE_ARG')
admin_port = getEnvVar('ADMIN_PORT')
admin_host = getEnvVar('AS_SERVICE_NAME')

force = 'false'
if shutdown_type.lower() == 'forced':
  force = 'true'

# Convert b64 encoded user key into binary
file = open('/weblogic-operator/introspector/userKeyNodeManager.secure', 'r')
contents = file.read()
file.close()
decoded=base64.decodestring(contents)

file = open('/tmp/userKeyNodeManager.secure.bin', 'wb')
file.write(decoded)
file.close()

# check if Coherence cluster exists in this domain
cohExists = doesCoherenceExist()

# If Coherence exists then we need to connect to admin server, else local server
if (cohExists):
  print ('Shutdown: Coherence cluster exists')
  connect_url = local_admin_protocol + '://' + admin_host + ':' + admin_port

  # must use force shutdown for admin server since Coherence MBeans cannot be found after
  # a graceful admin server restart
  if admin_name == server_name:
    force = 'true'

else:
  print ('Shutdown: Coherence cluster does not exist')
  connect_url = local_admin_protocol + '://' + service_name + ':' + local_admin_port

# Stay in loop until the server is shutdown if Coherence exists.  For non-Coherence
# just make a best effort
stayInConnectLoop = True
cohSafe = False
while (stayInConnectLoop):
  try:
    stayInConnectLoop = False
    print ('Shutdown: Connecting to server at ' + connect_url)
    connect(userConfigFile='/weblogic-operator/introspector/userConfigNodeManager.secure',
            userKeyFile='/tmp/userKeyNodeManager.secure.bin',
            url=connect_url,
            domainName=domain_name,
            domainDir=domain_path,
            nmType='plain')

    print('Shutdown: Successfully connected to server')
    if (cohExists):
      waitUntilCoherenceSafe()
      cohSafe = True

    print('Shutdown: Calling server shutdown with force = ' + force)
    shutdown(server_name, 'Server', ignoreSessions=ignore_sessions, timeOut=int(timeout), force=force, block='true', properties=None, waitForAllSessions=wait_for_all_sessions)
    print('Shutdown: Successfully shutdown the server')

  except Exception, e:
    print e
    print('Shutdown: Exception in connect or shutdown')
    if (cohExists and not cohSafe):
      print('Shutdown: Coherence not safe to shutdown. Sleeping before connect retry ...')
      stayInConnectLoop = True
      systime.sleep(10)
    else:
      try:
        shutdownUsingNodeManager(domain_name, domain_path)
        exit()
      except:
        exit(2)

# Exit WLST
exit()
