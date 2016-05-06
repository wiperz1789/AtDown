/*
 * Created on Mar 14, 2012
 * Created by Paul Gardner
 * 
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
 */


package connect.peermanager.messaging.azureus;

import torrentlib.DirectByteBuffer;

public interface 
AZUTMetaData 
{
	public static final int MSG_TYPE_REQUEST	= 0;
	public static final int MSG_TYPE_DATA		= 1;
	public static final int MSG_TYPE_REJECT		= 2;
	
	public int
	getMessageType();
	
	public int
	getPiece();
	
	public DirectByteBuffer
	getMetadata();
	
	public void
	setMetadata(
		DirectByteBuffer		metadata );
	
	public void
	destroy();
}
