package core;

// Java Imports
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.Socket;
//import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Queue;

import database.DBHelper;
// Custom Imports
//import dataAccessLayer.PlayerDAO;
import metadata.Constants;
import metadata.GameRequestTable;
//import model.Player;
import networking.request.GameRequest;
import networking.response.GameResponse;
import networking.response.ResponsePlayerLogout;
import utility.DataReader;

import java.sql.*;

/**
 * The GameClient class is an extension of the Thread class that represents an
 * individual client. Not only does this class holds the connection between the
 * client and server, it is also in charge of managing the connection to
 * actively receive incoming requests and send outgoing responses. This thread
 * lasts as long as the connection is alive.
 */
public class GameClient extends Thread {

	private GameServer server; // References GameServer instance
	private Socket mySocket; // Socket being used for this client
	private InputStream inputStream;
	private OutputStream outputStream; // For use with outgoing responses
	private DataInputStream dataInputStream; // For use with incoming requests
	private DataInputStream dataInput;
	private Player player;
	private boolean isPlaying;
	private Queue<GameResponse> updates; // Temporarily store responses for
											// client
	private int gamestate; // keep track of client's gamestate
	Connection c;

	/**
	 * Initialize the GameClient using the client socket and creating both input
	 * and output streams.
	 * 
	 * @param clientSocket
	 *            holds reference of the socket being used
	 * @param server
	 *            holds reference to the server instance
	 * @throws IOException
	 */
	public GameClient(Socket clientSocket, GameServer server)
			throws IOException {
		mySocket = clientSocket;
		this.server = server;
		updates = new LinkedList<GameResponse>();

		inputStream = mySocket.getInputStream();
		outputStream = mySocket.getOutputStream();
		dataInputStream = new DataInputStream(inputStream);
		gamestate = Constants.GAMESTATE_NOT_LOGGED_IN;
		player = new Player();
		c = null;
	}

	/**
	 * Holds the main loop that processes incoming requests by first identifying
	 * its type, then interpret the following data in each determined request
	 * class. Queued up responses created from each request class will be sent
	 * after the request is finished processing.
	 * 
	 * The loop exits whenever the isPlaying flag is set to false. One of these
	 * occurrences is triggered by a timeout. A timeout occurs whenever no
	 * activity is picked up from the client such as being disconnected.
	 */
	@Override
    public void run() {
        isPlaying = true;
        long lastActivity = System.currentTimeMillis();
        short requestCode = -1;

        while (isPlaying) {
            try {
                // Extract the size of the package from the data stream
                short requestLength = DataReader.readShort(dataInputStream);

                if (requestLength > 0) {
                    lastActivity = System.currentTimeMillis();
                    // Separate the remaining package from the data stream
                    byte[] buffer = new byte[requestLength];
                    inputStream.read(buffer, 0, requestLength);
                    dataInput = new DataInputStream(new ByteArrayInputStream(buffer));
                    // Extract the request code number
                    requestCode = DataReader.readShort(dataInput);
                    // Determine the type of request
                    GameRequest request = GameRequestTable.get(requestCode);
                    // If the request exists, process like following:
                    if (request != null) {
                    	request.setGameClient(this);
                        // Pass input stream to the request object
                        request.setDataInputStream(dataInput);
                        // Parse the input stream
                        request.parse();
                        // Interpret the data
                        request.doBusiness();
                        if (Constants.DEBUG && requestCode != Constants.C_HEARTBEAT)
                    		System.out.println(request);
                        // Retrieve any responses created by the request object
                        for (GameResponse response : request.getResponses()) {
                            // Transform the response into bytes and pass it into the output stream
                            //outputStream.write(response.constructResponseInBytes());
                        	updates.add(response);
                        }
                    }
                } else {
                    // If there was no activity for the last moments, exit loop
                    if ((System.currentTimeMillis() - lastActivity) / 1000 >= Constants.TIMEOUT_SECONDS) {
                        isPlaying = false;
                    }
                }
            } catch (Exception e) {
                System.err.println("Request [" + requestCode + "] Error:");
                System.err.println(e.getMessage());
                System.err.println("---");
                e.printStackTrace();
            }
        }

        System.out.println(new SimpleDateFormat("MM/dd/yyyy HH:mm:ss").format(new Date()));
        System.out.println("The client stops playing.");

        if (gamestate != Constants.GAMESTATE_NOT_LOGGED_IN){
	        // update database
        	openConnectionToDB();
        	String sql = "";
        	PreparedStatement pstmt = null;
        	try {
		        if (gamestate == Constants.GAMESTATE_PLAYING){
		        	// Update character
		        	sql = "UPDATE character SET char_x = ?, char_y = ?, char_z = ?, char_h = ?, char_p = ?, char_z = ? WHERE id = ?;)";
		        	pstmt = c.prepareStatement(sql);
		    		pstmt.setFloat(1, getPlayer().getCharacter().getX());
		    		pstmt.setFloat(2, getPlayer().getCharacter().getY());
		    		pstmt.setFloat(3, getPlayer().getCharacter().getZ());
		    		pstmt.setFloat(4, getPlayer().getCharacter().getH());
		    		pstmt.setFloat(5, getPlayer().getCharacter().getP());
		    		pstmt.setFloat(6, getPlayer().getCharacter().getR());
		    		pstmt.setInt(7, getPlayer().getCharacter().getId());
		        	pstmt.executeUpdate();
		        	// remove from server's online list
		        	server.removeActivePlayer(player.getId());
		        }
		        // do log out.
		    	sql = "UPDATE user SET is_online = 0 WHERE id = ?";
		    	if (Constants.DEBUG){
		    		System.out.printf("Disconnect user_id = %d", player.getId());
		    	}
		    	
		        pstmt = c.prepareStatement(sql);
		    	pstmt.setInt(1, player.getId());
		    	pstmt.executeUpdate();
		    	closeConnectionToDB();
		    	
		    	// let others know that this client logged out
        		ResponsePlayerLogout otherResponse = new ResponsePlayerLogout();
        		otherResponse.setCharacterID(getPlayer().getCharacter().getId());
        		getServer().addResponseForAllOnlinePlayers(getId(), otherResponse);
		    } catch (Exception e){
			e.printStackTrace();
		    }
        }
        // Remove this GameClient from the server
        server.deletePlayerThreadOutOfActiveThreads(getId());
    }

	public void stopClient() {
		isPlaying = false;
	}

	public GameServer getServer() {
		return server;
	}

	public Player getPlayer() {
		return player;
	}

	public Player setPlayer(Player player) {
		return this.player = player;
	}

	public boolean addResponseForUpdate(GameResponse response) {
		return updates.add(response);
	}

	/**
	 * Get all pending responses for this client.
	 * 
	 * @return all pending responses
	 */
	public Queue<GameResponse> getUpdates() {
		Queue<GameResponse> responseList = null;

		synchronized (this) {
			responseList = updates;
			updates = new LinkedList<GameResponse>();
		}

		return responseList;
	}

	public OutputStream getOutputStream() {
		return outputStream;
	}

	/**
	 * Remove all responses for this client.
	 */
	public void clearUpdateBuffer() {
		updates.clear();
	}

	/**
	 * Flush the response queue to the client
	 * 
	 * @return
	 */
	public void flushResponses() {
		for (GameResponse response : updates) {
			// Transform the response into bytes and pass it into the output
			// stream
			try {
				outputStream.write(response.constructResponseInBytes());
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		clearUpdateBuffer();
	}

	public String getIP() {
		return mySocket.getInetAddress().getHostAddress();
	}

	@Override
	public String toString() {
		String str = "";

		str += "-----" + "\n";
		str += getClass().getName() + "\n";
		str += "\n";

		for (Field field : getClass().getDeclaredFields()) {
			try {
				str += field.getName() + " - " + field.get(this) + "\n";
			} catch (Exception ex) {
				System.out.println(ex.getMessage());
			}
		}

		str += "-----";

		return str;
	}

	public int getGamestate() {
		return gamestate;
	}

	public void setGamestate(int gamestate) {
		this.gamestate = gamestate;
	}
	/**
	 * Close connection to the database
	 */
	public void closeConnectionToDB(){
		try {
			if ((c != null) && c.isValid(0))
				c.close();
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	/**
	 * Open connection to the database
	 */
	public void openConnectionToDB(){
		try {
			Class.forName("org.sqlite.JDBC");
			c = DriverManager.getConnection("jdbc:sqlite:hw2.db");
		} catch (Exception e) {
			System.err.println(e.getClass().getName() + ": " + e.getMessage());
		}		
	}
}
