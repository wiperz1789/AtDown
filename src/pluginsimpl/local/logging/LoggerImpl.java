/*
 * File    : LoggerImpl.java
 * Created : 28-Dec-2003
 * By      : parg
 * 
 * Azureus - a Java Bittorrent client
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the LICENSE file ).
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package pluginsimpl.local.logging;

/**
 * @author parg
 *
 */

import java.util.*;

import torrentlib.util.logging.ILogAlertListener;
import torrentlib.util.logging.LogAlert;
import torrentlib.util.logging.LogEvent;
import torrentlib.util.logging.FileLogging;
import torrentlib.util.logging.FileLoggingAdapter;
import torrentlib.util.platform.win32.PluginInterface;
import plugins.logging.FileLoggerAdapter;
import plugins.logging.LogAlertListener;
import plugins.logging.Logger;
import plugins.logging.LoggerAlertListener;
import plugins.logging.LoggerChannel;

public class 
LoggerImpl
	implements Logger
{
	private PluginInterface	pi;
	
	private List		channels 			 = new ArrayList();
	private Map			alert_listeners_map	 = new HashMap();
	private Map			alert_listeners_map2 = new HashMap();
	
	public
	LoggerImpl(
		PluginInterface	_pi )
	{
		pi	= _pi;
	}
	
	public PluginInterface
	getPluginInterface()
	{
		return( pi );
	}
	
	public LoggerChannel
	getChannel(
		String		name )
	{
		LoggerChannel	channel = new LoggerChannelImpl( this, name, false, false );
		
		channels.add( channel );
		
		return( channel );
	}
	
	public LoggerChannel
	getTimeStampedChannel(
		String		name )
	{
		LoggerChannel	channel = new LoggerChannelImpl( this, name, true, false );
		
		channels.add( channel );
		
		return( channel );
	}
	
	public LoggerChannel
	getNullChannel(
		String		name )
	{
		LoggerChannel	channel = new LoggerChannelImpl( this, name, true, true );
		
		channels.add( channel );
		
		return( channel );
	}
	
	public LoggerChannel[]
	getChannels()
	{
		LoggerChannel[]	res = new LoggerChannel[channels.size()];
		
		channels.toArray( res );
		
		return( res );
	}
	
	public void
	addAlertListener(
		final LoggerAlertListener		listener )
	{
		
		ILogAlertListener lg_listener = new ILogAlertListener() {
			public void alertRaised(LogAlert alert) {
				if (alert.err == null) {
					int type;

					if (alert.entryType == LogAlert.AT_INFORMATION) {
						type = LoggerChannel.LT_INFORMATION;
					} else if (alert.entryType == LogAlert.AT_WARNING) {
						type = LoggerChannel.LT_WARNING;
					} else {
						type = LoggerChannel.LT_ERROR;
					}

					listener.alertLogged(type, alert.text, alert.repeatable);

				} else
					listener.alertLogged(alert.text, alert.err, alert.repeatable);
			}

		};
				
		alert_listeners_map.put( listener, lg_listener );
		
		torrentlib.util.logging.Logger.addListener( lg_listener );
	}
	
	public void
	removeAlertListener(
		LoggerAlertListener		listener )
	{
		ILogAlertListener	lg_listener = (ILogAlertListener)alert_listeners_map.remove( listener );
		
		if ( lg_listener != null ){
			
			torrentlib.util.logging.Logger.removeListener( lg_listener );
		}
	}
	
	public void addAlertListener(final LogAlertListener listener) {
		ILogAlertListener lg_listener = new ILogAlertListener() {
			private HashSet set = new HashSet();
			public void alertRaised(LogAlert alert) {
				if (!alert.repeatable) {
					if (set.contains(alert.text)) {return;}
					set.add(alert.text); 
				}
				listener.alertRaised(alert);
			}
		};
		alert_listeners_map2.put(listener, lg_listener);
		torrentlib.util.logging.Logger.addListener(lg_listener);
	}
	
	public void removeAlertListener(LogAlertListener listener) {
		ILogAlertListener lg_listener = (ILogAlertListener)alert_listeners_map2.remove(listener);
		if (lg_listener != null){	
			torrentlib.util.logging.Logger.removeListener(lg_listener);
		}		
	}

	public void addFileLoggingListener(final FileLoggerAdapter listener) {
		FileLogging fileLogging = torrentlib.util.logging.Logger.getFileLoggingInstance();
		if (fileLogging == null)
			return;
		
		fileLogging.addListener(new PluginFileLoggerAdapater(fileLogging, listener));
	}

	public void removeFileLoggingListener(FileLoggerAdapter listener) {
		FileLogging fileLogging = torrentlib.util.logging.Logger.getFileLoggingInstance();
		if (fileLogging == null)
			return;

		// find listener and remove
		Object[] listeners = fileLogging.getListeners().toArray();
		for (int i = 0; i < listeners.length; i++) {
			if (listeners[i] instanceof PluginFileLoggerAdapater) {
				PluginFileLoggerAdapater l = (PluginFileLoggerAdapater) listeners[i];
				if (l.listener == listener) {
					fileLogging.removeListener(l);
				}
			}
		}
	}
	
	private class PluginFileLoggerAdapater extends FileLoggingAdapter {
		public FileLoggerAdapter listener;

		public PluginFileLoggerAdapater(FileLogging fileLogging, FileLoggerAdapter listener) {
			fileLogging.addListener(this);
			this.listener = listener;
		}

		public boolean logToFile(LogEvent event, StringBuffer lineOut) {
			return listener.logToFile(lineOut);
		}
	}
}
