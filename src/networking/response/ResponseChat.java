package networking.response;

import metadata.Constants;
import utility.GamePacket;

public class ResponseChat extends GameResponse {
	int flag;
	String senderName, chatMsg;
	
	/**
	 * Constructor. set the response code
	 */
	public ResponseChat() {
		// TODO Auto-generated constructor stub
		responseCode = Constants.S_CHAT;
	}
	
	/**
	 * Generate the packet to sent
	 * Info for the packet: flag [character name] chat msg
	 */
    @Override
    public byte[] constructResponseInBytes() {
        GamePacket packet = new GamePacket(responseCode);
    	// add the flag
        packet.addInt32(flag);
        
        // add more information if it is not a chat failure
        if (flag != Constants.CHAT_FAIL){
	        // add character name if it is private chat
		    if (flag == Constants.CHAT_PRIVATE){
		    	packet.addString(senderName);
	        }
	        // add the chat msg
	        packet.addString(chatMsg);
        }
        return packet.getBytes();
    }
    
    /**
     * Set private chat
     * @param name sender's name
     * @param msg chat message to be sent target character
     */
    public void setPrivateChat(String name, String msg){
    	// set flag to private
    	flag = Constants.CHAT_PRIVATE;
    	// set character name
    	senderName = name;
    	chatMsg = msg;
    }
    
    /**
     * set global chat
     * @param name name of the sender
     * @param msg chat message to be sent to everyone
     */
    public void setGlobalChat(String name, String msg){
    	// set flag to global
    	flag = Constants.CHAT_GLOBAL;
    	senderName = name;
    	chatMsg = msg;
    }
    
    /**
     * set private chat fail
     * set the flag to failure
     * this one is reserved for the sender
     */
    public void setPrivateChatFail(){
    	flag = Constants.CHAT_FAIL;
    }
}
