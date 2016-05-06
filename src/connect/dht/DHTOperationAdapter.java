/*
 * Created on 05-Mar-2005
 * Created by Paul Gardner
 * Copyright (C) Azureus Software, Inc, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 *
 */

package connect.dht;

import connect.dht.transport.DHTTransportContact;
import connect.dht.transport.DHTTransportValue;

/**
 * @author parg
 *
 */

public class 
DHTOperationAdapter
	implements DHTOperationListener
{
	private final DHTOperationListener	delegate;
	
	public
	DHTOperationAdapter()
	{
		delegate = null;
	}
	
	public
	DHTOperationAdapter(
		DHTOperationListener		_delegate )
	{
		delegate = _delegate;
	}
	
	public void
	searching(
		DHTTransportContact	contact,
		int					level,
		int					active_searches )
	{
		if ( delegate != null ){
			delegate.searching(contact, level, active_searches);
		}
	}
	
	public boolean
	diversified(
		String		desc )
	{
		if ( delegate != null ){
			return( delegate.diversified(desc));
		}else{
			return( true );}
	}
		
	public void
	found(
		DHTTransportContact	contact,
		boolean				is_closest )
	{
		if ( delegate != null ){
			delegate.found(contact, is_closest);
		}
	}
	
	public void
	read(
		DHTTransportContact	contact,
		DHTTransportValue	value )
	{
		if ( delegate != null ){
			delegate.read(contact, value);
		}
	}
	
	public void
	wrote(
		DHTTransportContact	contact,
		DHTTransportValue	value )
	{
		if ( delegate != null ){
			delegate.wrote(contact, value);
		}
	}
	
	public void
	complete(
		boolean				timeout )
	{
		if ( delegate != null ){
			delegate.complete(timeout);
		}
	}
}
