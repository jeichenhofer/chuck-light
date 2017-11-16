package chuck;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import chuck.defines.Connection;
import chuck.drivers.DMXDriver;
import chuck.threads.HeartBeatThread;
import chuck.threads.UDPServerThread;

/**
 * Chuck Light server application. Starts heartbeat thread and udp listener
 * thread, then waits for commands from the udp listener thread via the shared
 * queue.
 * 
 * @author Joseph Eichenhofer
 */
public class ServerApp {

	private DMXDriver dmx;
	private DatagramSocket serverSocket;

	private HeartBeatThread heartbeat;
	private UDPServerThread udpListen;

	/**
	 * Shared synchronous queue of commands to process. Producer is UDPServerThread, consumer is main execution.
	 */
	private BlockingQueue<WirelessCommand> commandQ = new LinkedBlockingQueue<WirelessCommand>();

	/**
	 * Start the server program.
	 * 
	 * @param args n/a
	 */
	public static void main(String[] args) {
		ServerApp serv = new ServerApp();
		serv.startServer();
	}

	/**
	 * Instantiate the DMX driver, datagram socket, and two threads. Then wait for
	 * commands from the wireless controller (via udp thread). Parses the command
	 * bytes, then interprets the change of state based on current state and the
	 * type of command.
	 */
	public void startServer() {
		try {
			// instantiate dmx driver
			dmx = new DMXDriver();
		} catch (IOException ex) {
			// fatal error if unable to instantiate driver
			ex.printStackTrace();
			System.exit(-1);
		}
		try {
			// instantiate server socket
			serverSocket = new DatagramSocket(Connection.DMX_PORT);
		} catch (SocketException ex) {
			// fatal error if unable to instantiate server socket
			ex.printStackTrace();
			System.exit(-1);
		}

		// start heartbeat thread
		heartbeat = new HeartBeatThread(serverSocket);
		heartbeat.start();
		// start udp listener thread
		udpListen = new UDPServerThread(serverSocket, commandQ);
		udpListen.start();

		// TODO: implement state machine and transitions

		// for testing, set to some random color
		try {
			dmx.setDMX(2, 128, 125, 64);
		} catch (IOException ex) {
			// treat ioexception as fatal error
			ex.printStackTrace();
			System.exit(-1);
		}

		WirelessCommand currCommand = null;
		boolean on = false;
		while (true) {
			// take element from queue, blocking until something is there
			try {
				currCommand = commandQ.take();
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

			switch (currCommand.getID()) {
			case Connection.COMMAND_PACKET_ID:
				System.out.println("received command packet");
				break;
			default:
				break;
			}

			// for now, just toggle the light on and off with each received packet
			try {
				if (on) {
					dmx.setDMX(1, 0);
				} else {
					dmx.setDMX(1, 255);
				}
			} catch (IOException e) {
				// treat ioexception as fatal error
				e.printStackTrace();
				System.exit(-1);
			}
		}
	}
}
