package chuck.threads;

import java.awt.Color;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import chuck.LightingProfile;
import chuck.ProfileManager;
import chuck.SceneManager;
import chuck.WirelessCommand;
import chuck.defines.Connection;
import chuck.defines.LightingDefines;
import chuck.defines.Modes;
import chuck.drivers.DMXDriver;

/**
 * Chuck Light server application. Starts heartbeat thread and udp listener
 * thread, then waits for commands from the udp listener thread via the shared
 * queue.
 * 
 * @author Joseph Eichenhofer and Christian Krueger
 */
public class ServerAppThread extends Thread {

	private DMXDriver dmx;
	private ProfileManager profiles;
	private SceneManager sceneManager;
	private ArrayList<LightingProfile> selectedLights;
	private byte currentState;
	private boolean serverRunning = false;
	private DatagramSocket serverSocket;
	private int chaseSceneDelay = 100;

	private HeartBeatThread heartbeat = null;
	private UDPServerThread udpListen = null;
	private ChaseThread chase = null;
	private HighlightThread highlight = null;
	private PresetVisualThread presetVisual = null;
	
	private int currentLightIndex;
	private int currentPresetIndex;

	/**
	 * Shared synchronous queue of commands to process. Producer is UDPServerThread, consumer is main execution.
	 */
	private BlockingQueue<WirelessCommand> commandQ;

	public ServerAppThread(DMXDriver driver, ProfileManager profManager) {
		super();
		dmx = driver;
		profiles = profManager;
	}
	
	/**
	 * Instantiate the DMX driver, datagram socket, and two threads. Then wait for
	 * commands from the wireless controller (via udp thread). Parses the command
	 * bytes, then interprets the change of state based on current state and the
	 * type of command.
	 */
	public void run() {
		
		currentState = Modes.IDLE;
		currentLightIndex = 0;
		currentPresetIndex = 0;
		boolean sendHeartbeat = false;
		
		try {
			// instantiate server socket
			serverSocket = new DatagramSocket(Connection.DMX_PORT);
			System.out.println("Server Socket Initialized");
		} catch (SocketException ex) {
			// fatal error if unable to instantiate server socket
			ex.printStackTrace();
			System.exit(-1);
		}
		
		//create new command queue
		commandQ = new LinkedBlockingQueue<WirelessCommand>();

		// start heartbeat thread
		heartbeat = new HeartBeatThread(serverSocket, currentState);
		heartbeat.setPriority(Thread.NORM_PRIORITY);
		heartbeat.start();
		
		System.out.println("Heartbeat Thread Started");
		// start udp listener thread
		udpListen = new UDPServerThread(serverSocket, commandQ);
		udpListen.setPriority(Thread.MAX_PRIORITY);
		udpListen.start();
		System.out.println("UDP Thread Started");
		
		//load scenes
		//sceneManager = new SceneManager(dmx.getDmx());
		sceneManager = new SceneManager(new int[513]);
		
		sceneManager.updateSceneFile();
				
		WirelessCommand currCommand = null;
		serverRunning = true;
		
		if(profiles.getLightCount() != 0) {
			try {
				profiles.getLight(0).setDimmerValue(255);
				profiles.getLight(0).setColor(Color.BLUE);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
		} else {
			System.out.println("No lights");
		}
		while (serverRunning) {
			// take element from queue, blocking until something is there
			try {
				currCommand = commandQ.poll(1, TimeUnit.SECONDS);
				
				if (currCommand == null)
					continue;
				
				if(!heartbeat.getConnected())
				{
					heartbeat.setAddress(currCommand.getSender_ip());
				}
			} catch (InterruptedException ex) {
				// for now, treat interruptedexception as fatal error
				ex.printStackTrace();
				System.exit(-1);
			}

			if (!currCommand.parse()) {
				// if unable to parse the command, ignore it
				System.out.println("Received invalid packet");
				continue;
			}

			sendHeartbeat = false;
			
			switch (currCommand.getPacketType()) {
			case Connection.DATA_PACKET_ID:
			/* ---------- Main State Machine ------------------- */
				/*for debug purposes*/
				switch(currCommand.getUserActionData()){
				case Connection.UP:
					System.out.println("up");
					break;
				case Connection.DOWN:
					System.out.println("down");
					break;
				case Connection.LEFT:
					System.out.println("left");
					break;
				case Connection.RIGHT:
					System.out.println("right");
					break;
				case Connection.B1:
					System.out.println("b1");
					break;
				case Connection.B2:
					System.out.println("b2");
					break;
				case Connection.PS2:
					System.out.println("ps2");
					break;
				case Connection.B12:
					System.out.println("b12");
					break;
				case Connection.KONAMI:
					System.out.println("ko");
					currentState = Modes.PARTY;
					sendHeartbeat = true;
					break;
				case Connection.REV_KONAMI:
					System.out.println("rko");
					break;
				}
				
				switch(currentState)
				{
					case Modes.CHASE:
						if(currCommand.getDataType() != Connection.USER_ACTION_DATA){
							sendHeartbeat = true;
							break;
						}
						switch(currCommand.getUserActionData()){
						case Connection.UP:
							if(chaseSceneDelay < LightingDefines.MAX_CHASE_DELAY){
								chase.setSceneDelay(chaseSceneDelay+=100);
							}
							break;
						case Connection.DOWN:
							if(chaseSceneDelay > LightingDefines.MIN_CHASE_DELAY){
								chase.setSceneDelay(chaseSceneDelay-=100);
							}
							break;
						case Connection.B2:
							currentState = Modes.IDLE;
							chase.redrum();
							try {
								chase.join();
							} catch (InterruptedException e1) {
								// TODO Auto-generated catch block
								e1.printStackTrace();
								System.exit(-1);
							}
							sendHeartbeat = true;
							break;
						}
					break;
					case Modes.IDLE:
						if(currCommand.getDataType() != Connection.USER_ACTION_DATA){
							sendHeartbeat = true;
							break;
						}
						switch(currCommand.getUserActionData()){
						case Connection.LEFT:
							/*try {
								dmx.setDMX(sceneManager.getLastScene().getDmxVals());
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}*/
							System.out.println(Arrays.toString(sceneManager.getLastScene().getDmxVals()));
							break;
						case Connection.RIGHT:
							/*try {
								dmx.setDMX(sceneManager.getNextScene().getDmxVals());
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}*/
							System.out.println(Arrays.toString(sceneManager.getNextScene().getDmxVals()));
							break;
						case Connection.B1:
							currentState = Modes.LIGHT_SELECTION;
							//TODO Highlight first Light
							//sceneManager.setCurrentScene(dmx.getDmx());
							sceneManager.setCurrentScene(new int[513]);
							highlight = new HighlightThread(dmx);
							highlight.addLight(profiles.getLight(currentLightIndex));
							highlight.start();
							sendHeartbeat = true;
							break;
						case Connection.B2:
							if(sceneManager.getSceneCount() > 1){
								currentState = Modes.CHASE;
								chase = new ChaseThread(chaseSceneDelay, sceneManager.getSceneArray(), dmx);
								chase.start();
								sendHeartbeat = true;
							} else {
								System.out.println("Can't play chase with less than 2 Scenes");
							}
							break;
						case Connection.PS2:
							//adds scene to list
							if(sceneManager.getCurrentIndex() == -1)
							{
								sceneManager.addScene(sceneManager.getCurrentScene());
								sceneManager.updateSceneFile();
							}
							break;
						case Connection.PS2_LONG:
							if(sceneManager.getCurrentIndex() != -1)
							{
								sceneManager.deleteScene();
								//dmx.setDMX(sceneManager.getCurrentScene().getDmxVals());
							}
							break;
						case Connection.KONAMI:
							currentState = Modes.PARTY;
							sendHeartbeat = true;
							break;
						case Connection.REV_KONAMI:
							currentState = Modes.SCARY;
							sendHeartbeat = true;
							break;
						}
					break;
					case Modes.LIGHT_SELECTION:
						if(currCommand.getDataType() != Connection.USER_ACTION_DATA){
							sendHeartbeat = true;
							break;
						}
						switch(currCommand.getUserActionData()){
						case Connection.LEFT:
							//TODO Highlight Prevous Light
							break;
						case Connection.RIGHT:
							//TODO Highlight Next Light
							break;
						case Connection.PS2:
							//TODO Highlight add light to selected lights
							break;
						case Connection.B1:
							currentState = Modes.CONTROL_SELECTION;
							//creates carbon copy of lights as is
							//TODO start colorwheel visualization
							sendHeartbeat = true;
							selectedLights = highlight.redrum();
							try {
								highlight.join();
							} catch (InterruptedException e1) {
								// TODO Auto-generated catch block
								e1.printStackTrace();
								System.exit(-1);
							}
							//TODO join threads
							System.out.println("Selected lights # = " + selectedLights.size());
							presetVisual = new PresetVisualThread(dmx, selectedLights);
							presetVisual.start();
							break;
						case Connection.B2:
							highlight.redrum();
							try {
								highlight.join();
							} catch (InterruptedException e1) {
								// TODO Auto-generated catch block
								e1.printStackTrace();
								System.exit(-1);
							}
							currentState = Modes.IDLE;
							sendHeartbeat = true;
							break;
						}
					break;
					case Modes.CONTROL_SELECTION: //TODO need stateful information - what mode is highlighted
						if(currCommand.getDataType() != Connection.USER_ACTION_DATA){
							sendHeartbeat = true;
							break;
						}
						switch(currCommand.getUserActionData()){
						case Connection.LEFT:
							//TODO Position Dependant
							break;
						case Connection.RIGHT:
							//TODO Position Dependant
							break;
						case Connection.B1:
							//TODO Position Dependant
							currentState = Modes.PRESET;

							presetVisual.redrum();
							try {
								presetVisual.join();
							} catch (InterruptedException e1) {
								// TODO Auto-generated catch block
								e1.printStackTrace();
								System.exit(-1);
							}
							sendHeartbeat = true;
							break;
						case Connection.B2:
							currentState = Modes.LIGHT_SELECTION;
							try {
								dmx.setDMX(sceneManager.getCurrentScene().getDmxVals());
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
								System.exit(-1);
							}
							//TODO copy dmxTempVals to Light
							sendHeartbeat = true;
							break;
						}	
					break;
					case Modes.COLOR_WHEEL: 
						if(currCommand.getDataType() == Connection.USER_ACTION_DATA){
							switch(currCommand.getUserActionData()){
							case Connection.B1:
								//TODO SaveChanges
								currentState = Modes.LIGHT_SELECTION;
								sendHeartbeat = true;
								break;
							case Connection.B2:
								currentState = Modes.CONTROL_SELECTION;
								//TODO start Colorwheel visulization
								sendHeartbeat = true;
								break;
							}
						}
						else if(currCommand.getDataType() == Connection.JOYSTIC_DATA)
						{
							System.out.println(Arrays.toString(currCommand.getJoystickData()));
							//TODO RGB stuff with Joystick
						}
						else
						{
							//bad mode
							sendHeartbeat = true;
							break;
						}
					break;
					case Modes.DMX: //TODO needs stateful data - current DMX channel selected
						if(currCommand.getDataType() != Connection.USER_ACTION_DATA){
							sendHeartbeat = true;
							break;
						}
						switch(currCommand.getUserActionData()){
						case Connection.LEFT:
							//TODO Control Last DMX Channel
							break;
						case Connection.RIGHT:
							//TODO Control Next DMX Channel
							break;
						case Connection.UP:
							//TODO Increase DMX value @ channel
							break;
						case Connection.DOWN:
							//TODO Decrease DMX value @ channel
							break;
						case Connection.B1:
							//TODO Save Changes
							currentState = Modes.LIGHT_SELECTION;
							sendHeartbeat = true;
							break;
						case Connection.B2:
							currentState = Modes.CONTROL_SELECTION;
							//start DMX mode visulization 
							sendHeartbeat = true;
							break;
						}
					break;
					case Modes.PRESET:
						switch(currCommand.getUserActionData()){
						case Connection.LEFT:
							if(--currentPresetIndex < 0){
								currentPresetIndex = LightingDefines.PRESETS.length - 1;
							}
							for (LightingProfile light : selectedLights) {
								try {
									light.setColor(LightingDefines.PRESETS[currentPresetIndex]);
								} catch (IOException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
									System.exit(-1);
								}
							}
							break;
						case Connection.RIGHT:
							if(++currentPresetIndex >= LightingDefines.PRESETS.length){
								currentPresetIndex = 0;
							}
							for (LightingProfile light : selectedLights) {
								try {
									light.setColor(LightingDefines.PRESETS[currentPresetIndex]);
								} catch (IOException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
									System.exit(-1);
								}
							}
							break;
						case Connection.B1:
							//TODO Save
							sceneManager.setCurrentScene(dmx.getDmx());
							currentState = Modes.LIGHT_SELECTION;
							sendHeartbeat = true;
							break;
						case Connection.B2:
							currentState = Modes.CONTROL_SELECTION;
							presetVisual.start();
							sendHeartbeat = true;
							break;
						}
					break;
					case Modes.PARTY:
						switch(currCommand.getUserActionData()){
						case Connection.B12:
							//TODO Save
							currentState = Modes.IDLE;
							sendHeartbeat = true;
							break;
						}
					break;
					case Modes.SCARY:
						switch(currCommand.getUserActionData()){
						case Connection.B12:
							//TODO Save
							currentState = Modes.IDLE;
							sendHeartbeat = true;
							break;
						}
					break;
					default:
						System.out.println("State out of bounds!");
					break;
				}
				break;
			case Connection.POLL_REPLY_PACKET_ID:
				//Do mode matching maybe?
				System.out.println("Poll Reply");
				break;
			case Connection.POLL_PACKET_ID:
				//Do mode matching maybe?
				System.out.println("Poll");
				break;
			default:
				System.out.println("Unkown Packet Type");
			break;
			}
			
			if(sendHeartbeat)
			{
				heartbeat.setCurrentState(currentState);
				heartbeat.sendHeartbeat();
			}
		}
	}
	
	public void stopServer(){
		if(udpListen != null)
			try {
				udpListen.redrum();
				udpListen.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		
		if(heartbeat != null)
			try {
				heartbeat.redrum();
				heartbeat.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		
		if(serverSocket != null)
			serverSocket.close();
		
		/*try {
			dmx.clearDMX();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}*/
		
		serverRunning = false;
	}
	
	public ProfileManager getProfileManager(){
		return profiles;
	}
	
	public boolean isServerRunning()
	{
		return serverRunning;
		
	}
	
}