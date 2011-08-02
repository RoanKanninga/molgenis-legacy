package org.molgenis.util;

import java.io.PrintStream;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.logging.Log;

/*
 * The idea is to use this in every exception, than exception is
 * handle in the same way.
 * At the moment after every exception the JVM quites. This should
 * be changed in future, for example quite in test & development
 * but continue in production (some flag in for example molgenis.properties, could
 * be an idea).
 * 
 * @author Joris Lops
 */
public class HandleException
{	
	public static void handle(Throwable t, Log l) {
		printSimpleStackTrace(t, l);
		l.error("Detailed stack trace:");
		ExceptionUtils.printRootCauseStackTrace(t);
		System.exit(1);		
	}
	
	public static void handle(Throwable t) {
		handle(t, System.err);
	}
	
	public static void handle(Throwable t, PrintStream out) {
		printSimpleStackTrace(t, out);
		out.println("Detailed stack trace:");
		ExceptionUtils.printRootCauseStackTrace(t);
		System.exit(1);		
	}
	
	private static void printSimpleStackTrace(Throwable t, PrintStream o) {
		Throwable cause = t.getCause();
		Throwable prevCause = null;
		String tabs = "";
		while (cause != null && prevCause != cause)
		{
			o.println((String.format("%sCause: %s", tabs,
					cause.getMessage())));
			tabs += "\t";

			prevCause = cause;
			cause = cause.getCause();
		}			
	}
	
	private static void printSimpleStackTrace(Throwable t, Log l) {
		Throwable cause = t.getCause();
		Throwable prevCause = null;
		String tabs = "";
		while (cause != null && prevCause != cause)
		{
			l.error(String.format("%sCause: %s", tabs,
					cause.getMessage()));
			tabs += "\t";

			prevCause = cause;
			cause = cause.getCause();
		}			
	}
}
