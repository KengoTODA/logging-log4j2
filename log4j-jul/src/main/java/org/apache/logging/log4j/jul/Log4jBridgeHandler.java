/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.jul;

// note: NO import of Logger, LogManager etc. to prevent conflicts JUL/log4j
import java.util.logging.LogRecord;

import org.apache.logging.log4j.spi.ExtendedLogger;
import org.apache.logging.log4j.status.StatusLogger;


/**
 * Bridge from JUL to log4j2.
 * This is an alternative to log4j.jul.LogManager (running as complete JUL replacement),
 * especially useful for webapps running on a container for which the LogManager can or
 * should not be used.
 *
 * Installation/usage:
 * - programmatically by calling install() method,
 *    e.g. inside ServletContextListener static-class-init. or contextInitialized()
 * - declaratively inside JUL's logging.properties:
 *    handlers = org.apache.logging.log4j.jul.Log4jBridgeHandler
 *    (note: in a webapp running on Tomcat, you may create a WEB-INF/classes/logging.properties
 *     file to configure JUL for this webapp only)
 *
 * Configuration (in logging.properties):
 * - Log4jBridgeHandler.suffixToAppend
 *        String, suffix to append to JUL logger names, to easily recognize bridged log messages.
 *        A dot "." is automatically prepended, so configuration for the basis logger is used
 *        Example:  suffixToAppend = _JUL
 * - Log4jBridgeHandler.propagateLevels  boolean, "true" to automatically propagate log4j log levels to JUL.
 * - Log4jBridgeHandler.sysoutDebug  boolean
 *
 * Restrictions:
 * - Manually given source/location info in JUL (e.g. entering(), exiting(), throwing(), logp(), logrb() )
 *    will NOT be considered, i.e. gets lost in log4j logging.
 *
 * TODO: performance note; log level propagation
 *
 * @author Thies Wellpott (twapache@online.de)
 * @author authors of original org.slf4j.bridge.SLF4JBridgeHandler (ideas and some basis from there)
 * @author authors of original ch.qos.logback.classic.jul.LevelChangePropagator (ideas and some basis from there)
 */
public class Log4jBridgeHandler extends java.util.logging.Handler {
    private static final org.apache.logging.log4j.Logger SLOGGER = StatusLogger.getLogger();

    // the caller of the logging is java.util.logging.Logger (for location info)
    private static final String FQCN = java.util.logging.Logger.class.getName();
    private static final String UNKNOWN_LOGGER_NAME = "unknown.jul.logger";
    private static final java.util.logging.Formatter julFormatter = new java.util.logging.SimpleFormatter();

    private boolean debugOutput = false;
    private String suffixToAppend = null;
    private boolean installAsLevelPropagator = false;


    /**
     * Adds a Log4jBridgeHandler instance to JUL's root logger.
     * This is a programmatic alternative to specify "handlers = org.apache.logging.log4j.jul.Log4jBridgeHandler"
     * in logging.properties.
     * This handler will redirect JUL logging to log4j2.
     * However, only logs enabled in JUL will be redirected. For example, if a log
     * statement invoking a JUL logger is disabled, then the corresponding non-event
     * will <em>not</em> reach Log4jBridgeHandler and cannot be redirected.
     *
     * @param removeHandlersForRootLogger  remove all other installed handlers on JUL root level
     */
    public static void install(boolean removeHandlersForRootLogger) {
        java.util.logging.Logger rootLogger = getJulRootLogger();
        if (removeHandlersForRootLogger) {
            for (java.util.logging.Handler hdl : rootLogger.getHandlers()) {
                rootLogger.removeHandler(hdl);
            }
        }
        rootLogger.addHandler(new Log4jBridgeHandler());
        // note: filter-level of Handler defaults to ALL, so nothing to do here
    }

    private static java.util.logging.Logger getJulRootLogger() {
        return java.util.logging.LogManager.getLogManager().getLogger("");
    }


    /**
     * Initialize this handler. Read out configuration.
     */
    public Log4jBridgeHandler() {
        final java.util.logging.LogManager julLogMgr = java.util.logging.LogManager.getLogManager();
        String className = this.getClass().getName();
        debugOutput = Boolean.parseBoolean(julLogMgr.getProperty(className + ".sysoutDebug"));
        if (debugOutput) {
            new Exception("DIAGNOSTIC ONLY (sysout):  Log4jBridgeHandler instance created (" + this + ")")
                    .printStackTrace(System.out);		// is no error thus no syserr
        }

        suffixToAppend = julLogMgr.getProperty(className + ".appendSuffix");
        if (suffixToAppend != null) {
            suffixToAppend = suffixToAppend.trim();		// remove spaces
            if (suffixToAppend.isEmpty()) {
                suffixToAppend = null;
            } else if (suffixToAppend.charAt(0) != '.') {		// always make it a sub-logger
                suffixToAppend = '.' + suffixToAppend;
            }
        }
        installAsLevelPropagator = Boolean.parseBoolean(julLogMgr.getProperty(className + ".propagateLevels"));
        // TODO really do install
        if (installAsLevelPropagator) {
        	SLOGGER.warn("Log4jBridgeHandler.propagateLevels is currently NOT implemented. Call Log4jBridgeHandler.initJulLogLevels() !");
        }

        SLOGGER.debug("Log4jBridgeHandler init. with: suffix='{}', lP={}",
        		suffixToAppend, installAsLevelPropagator);
    }


    @Override
    public void close() {
        if (debugOutput) {
            System.out.println("sysout:  Log4jBridgeHandler close(): " + this);
        }
    }


    @Override
    public void publish(LogRecord record) {
        // silently ignore null records
        if (record == null) {
            return;
        }

        org.apache.logging.log4j.Logger log4jLogger = getLog4jLogger(record);
        String msg = julFormatter.formatMessage(record);		// use JUL's implementation to get real msg
        /* log4j allows nulls:
        if (msg == null) {
            // this is a check to avoid calling the underlying logging system
            // with a null message. While it is legitimate to invoke JUL with
            // a null message, other logging frameworks do not support this.
            msg = "<null log msg>";
        } */
        org.apache.logging.log4j.Level log4jLevel = LevelTranslator.toLevel(record.getLevel());
        Throwable thrown = record.getThrown();
        if (log4jLogger instanceof ExtendedLogger) {
            // relevant for location information
            try {
                ((ExtendedLogger) log4jLogger).logIfEnabled(FQCN, log4jLevel, null, msg, thrown);
            } catch (NoClassDefFoundError e) {
                // sometimes there are problems with log4j.ExtendedStackTraceElement, so try a workaround
                log4jLogger.warn("Log4jBridgeHandler: ignored exception when calling 'ExtendedLogger': {}", e.toString());
                log4jLogger.log(log4jLevel, msg, thrown);
            }
        } else {
            log4jLogger.log(log4jLevel, msg, thrown);
        }
    }


    @Override
    public void flush() {
        // nothing to do
    }


    /**
     * Return the Logger instance that will be used for logging.
     * Handles null name case and appends configured suffix.
     */
    protected org.apache.logging.log4j.Logger getLog4jLogger(LogRecord record) {
        String name = record.getLoggerName();
        if (name == null) {
            name = UNKNOWN_LOGGER_NAME;
        } else if (suffixToAppend != null) {
            name += suffixToAppend;
        }
        return org.apache.logging.log4j.LogManager.getLogger(name);
    }

}