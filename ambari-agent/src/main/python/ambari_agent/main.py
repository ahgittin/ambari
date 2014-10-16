#!/usr/bin/env python

'''
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
'''

import logging.handlers
import signal
from optparse import OptionParser
import sys
import traceback
import os
import time
import platform
import ConfigParser
import ProcessHelper
from Controller import Controller
import AmbariConfig
from NetUtil import NetUtil
from PingPortListener import PingPortListener
import hostname
from DataCleaner import DataCleaner
import socket
logger = logging.getLogger()

formatstr = "%(levelname)s %(asctime)s %(filename)s:%(lineno)d - %(message)s"
agentPid = os.getpid()
config = AmbariConfig.AmbariConfig()
configFile = config.CONFIG_FILE
two_way_ssl_property = config.TWO_WAY_SSL_PROPERTY

IS_WINDOWS = platform.system() == "Windows"

if IS_WINDOWS:
  from HeartbeatHandlers_windows import bind_signal_handlers
else:
  from HeartbeatStopHandler_linux import bind_signal_handlers

def setup_logging(verbose):
  formatter = logging.Formatter(formatstr)
  rotateLog = logging.handlers.RotatingFileHandler(AmbariConfig.AmbariConfig.getLogFile(), "a", 10000000, 25)
  rotateLog.setFormatter(formatter)
  logger.addHandler(rotateLog)

  if verbose:
    logging.basicConfig(format=formatstr, level=logging.DEBUG, filename=AmbariConfig.AmbariConfig.getLogFile())
    logger.setLevel(logging.DEBUG)
    logger.info("loglevel=logging.DEBUG")
  else:
    logging.basicConfig(format=formatstr, level=logging.INFO, filename=AmbariConfig.AmbariConfig.getLogFile())
    logger.setLevel(logging.INFO)
    logger.info("loglevel=logging.INFO")


def update_log_level(config):
  # Setting loglevel based on config file
  try:
    loglevel = config.get('agent', 'loglevel')
    if loglevel is not None:
      if loglevel == 'DEBUG':
        logging.basicConfig(format=formatstr, level=logging.DEBUG, filename=AmbariConfig.AmbariConfig.getLogFile())
        logger.setLevel(logging.DEBUG)
        logger.info("Newloglevel=logging.DEBUG")
      else:
        logging.basicConfig(format=formatstr, level=logging.INFO, filename=AmbariConfig.AmbariConfig.getLogFile())
        logger.setLevel(logging.INFO)
        logger.debug("Newloglevel=logging.INFO")
  except Exception, err:
    logger.info("Default loglevel=DEBUG")


#  ToDo: move that function inside AmbariConfig
def resolve_ambari_config():
  global config
  configPath = os.path.abspath(AmbariConfig.AmbariConfig.getConfigFile())

  try:
    if os.path.exists(configPath):
      config.read(configPath)
    else:
      raise Exception("No config found at {0}, use default".format(configPath))

  except Exception, err:
    logger.warn(err)


def perform_prestart_checks(expected_hostname):
  # Check if current hostname is equal to expected one (got from the server
  # during bootstrap.
  global config

  if expected_hostname is not None:
    current_hostname = hostname.hostname(config)
    if current_hostname != expected_hostname:
      print("Determined hostname does not match expected. Please check agent "
            "log for details")
      msg = "Ambari agent machine hostname ({0}) does not match expected ambari " \
            "server hostname ({1}). Aborting registration. Please check hostname, " \
            "hostname -f and /etc/hosts file to confirm your " \
            "hostname is setup correctly".format(current_hostname, expected_hostname)
      logger.error(msg)
      sys.exit(1)
  # Check if there is another instance running
  # if os.path.isfile(ProcessHelper.pidfile):
  #   print("%s already exists, exiting" % ProcessHelper.pidfile)
  #   sys.exit(1)
  # # check if ambari prefix exists
  elif config.has_option('agent', 'prefix') and not os.path.isdir(os.path.abspath(config.get('agent', 'prefix'))):
    msg = "Ambari prefix dir %s does not exists, can't continue" \
          % config.get("agent", "prefix")
    logger.error(msg)
    print(msg)
    sys.exit(1)
  elif not config.has_option('agent', 'prefix'):
    msg = "Ambari prefix dir %s not configured, can't continue"
    logger.error(msg)
    print(msg)
    sys.exit(1)


def daemonize():
  # Daemonize current instance of Ambari Agent
  # Currently daemonization is done via /usr/sbin/ambari-agent script (nohup)
  # and agent only dumps self pid to file
  if not os.path.exists(ProcessHelper.piddir):
    os.makedirs(ProcessHelper.piddir, 0755)

  pid = str(os.getpid())
  file(ProcessHelper.pidfile, 'w').write(pid)


def stop_agent():
# stop existing Ambari agent
  pid = -1
  try:
    f = open(ProcessHelper.pidfile, 'r')
    pid = f.read()
    pid = int(pid)
    f.close()
    os.kill(pid, signal.SIGTERM)
    time.sleep(5)
    if os.path.exists(ProcessHelper.pidfile):
      raise Exception("PID file still exists.")
    os._exit(0)
  except Exception, err:
    if pid == -1:
      print ("Agent process is not running")
    else:
      os.kill(pid, signal.SIGKILL)
    os._exit(1)


# event - event, that will be passed to Controller and NetUtil to make able to interrupt loops form outside process
# we need this for windows os, where no sigterm available
def main(heartbeat_stop_callback):
  global config
  parser = OptionParser()
  parser.add_option("-v", "--verbose", dest="verbose", action="store_true", help="verbose log output", default=False)
  parser.add_option("-e", "--expected-hostname", dest="expected_hostname", action="store",
                    help="expected hostname of current host. If hostname differs, agent will fail", default=None)
  (options, args) = parser.parse_args()

  expected_hostname = options.expected_hostname

  setup_logging(options.verbose)

  default_cfg = {'agent': {'prefix': '/home/ambari'}}
  config.load(default_cfg)

  bind_signal_handlers(agentPid)

  if (len(sys.argv) > 1) and sys.argv[1] == 'stop':
    stop_agent()

  # Check for ambari configuration file.
  resolve_ambari_config()

  # Starting data cleanup daemon
  data_cleaner = None
  if config.has_option('agent', 'data_cleanup_interval') and int(config.get('agent','data_cleanup_interval')) > 0:
    data_cleaner = DataCleaner(config)
    data_cleaner.start()

  perform_prestart_checks(expected_hostname)

  if not IS_WINDOWS:
    daemonize()

  # Starting ping port listener
  try:
    ping_port_listener = PingPortListener(config)
  except Exception as ex:
    err_message = "Failed to start ping port listener of: " + str(ex)
    logger.error(err_message)
    sys.stderr.write(err_message)
    sys.exit(1)
  ping_port_listener.start()

  update_log_level(config)

  server_hostname = config.get('server', 'hostname')
  server_url = config.get_api_url()

  try:
    server_ip = socket.gethostbyname(server_hostname)
    logger.info('Connecting to Ambari server at %s (%s)', server_url, server_ip)
  except socket.error:
    logger.warn("Unable to determine the IP address of the Ambari server '%s'", server_hostname)

  # Wait until server is reachable
  netutil = NetUtil(heartbeat_stop_callback)
  retries, connected = netutil.try_to_connect(server_url, -1, logger)
  # Ambari Agent was stopped using stop event
  if connected:
    # Launch Controller communication
    controller = Controller(config, heartbeat_stop_callback)
    controller.start()
    controller.join()
  #stop_agent()
  logger.info("finished")

if __name__ == "__main__":
  heartbeat_stop_callback = bind_signal_handlers(agentPid)

  main(heartbeat_stop_callback)
