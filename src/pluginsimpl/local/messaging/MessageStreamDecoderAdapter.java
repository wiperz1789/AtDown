/*
 * Created on Feb 21, 2005
 * Created by Alon Rohter
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

package pluginsimpl.local.messaging;




import plugins.messaging.Message;
import plugins.messaging.MessageStreamDecoder;
import java.io.IOException;
import java.nio.ByteBuffer;

import pluginsimpl.local.network.TransportImpl;


/**
 *
 */
public class MessageStreamDecoderAdapter implements connect.peer.messaging.MessageStreamDecoder {
  private final MessageStreamDecoder plug_decoder;
  
  
  public MessageStreamDecoderAdapter( MessageStreamDecoder plug_decoder ) {
    this.plug_decoder = plug_decoder;
  }
  
  
  public int performStreamDecode( controller.networkmanager.Transport transport, int max_bytes ) throws IOException {
    return plug_decoder.performStreamDecode( new TransportImpl( transport ), max_bytes );
  }
  
  
  public int getPercentDoneOfCurrentMessage() {
    return -1;  //not implemented
  }
  
  
  public connect.peer.messaging.Message[] removeDecodedMessages() {
    Message[] plug_msgs = plug_decoder.removeDecodedMessages();
    
    if( plug_msgs == null || plug_msgs.length < 1 ) {
      return null;
    }
    
        connect.peer.messaging.Message[] core_msgs = new connect.peer.messaging.Message[ plug_msgs.length ];
    
    for( int i=0; i < plug_msgs.length; i++ ) {
      core_msgs[i] = new MessageAdapter( plug_msgs[i] );
    }
    
    return core_msgs;
  }
  
  public int getProtocolBytesDecoded() {  return plug_decoder.getProtocolBytesDecoded();  }

  public int getDataBytesDecoded() {  return plug_decoder.getDataBytesDecoded();  }

  public void pauseDecoding() {  plug_decoder.pauseDecoding();  } 

  public void resumeDecoding() {  plug_decoder.resumeDecoding();  } 
  
  public ByteBuffer destroy() {  return plug_decoder.destroy();  }

}