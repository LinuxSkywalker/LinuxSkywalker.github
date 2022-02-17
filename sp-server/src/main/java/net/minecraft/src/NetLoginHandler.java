package net.minecraft.src;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.Socket;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import javax.crypto.SecretKey;
import net.minecraft.server.MinecraftServer;

public class NetLoginHandler extends NetHandler {
	/** The Random object used to generate serverId hex strings. */
	private static Random rand = new Random();

	/** The 4 byte verify token read from a Packet252SharedKey */
	private byte[] verifyToken;

	/** Reference to the MinecraftServer object. */
	private final MinecraftServer mcServer;
	public final TcpConnection myTCPConnection;

	/**
	 * Returns if the login handler is finished and can be removed. It is set to
	 * true on either error or successful login.
	 */
	public boolean finishedProcessing = false;

	/** While waiting to login, if this field ++'s to 600 it will kick you. */
	private int loginTimer = 0;
	private String clientUsername = null;
	private volatile boolean field_72544_i = false;

	/** server ID that is randomly generated by this login handler. */
	private String loginServerId = "";
	private boolean field_92079_k = false;

	/** Secret AES key obtained from the client's Packet252SharedKey */
	private SecretKey sharedKey = null;

	public NetLoginHandler(MinecraftServer par1MinecraftServer, Socket par2Socket, String par3Str) throws IOException {
		this.mcServer = par1MinecraftServer;
		this.myTCPConnection = new TcpConnection(par1MinecraftServer.getLogAgent(), par2Socket, par3Str, this,
				par1MinecraftServer.getKeyPair().getPrivate());
		this.myTCPConnection.field_74468_e = 0;
	}

	/**
	 * Logs the user in if a login packet is found, otherwise keeps processing
	 * network packets unless the timeout has occurred.
	 */
	public void tryLogin() {
		if (this.field_72544_i) {
			this.initializePlayerConnection();
		}

		if (this.loginTimer++ == 600) {
			this.kickUser("Took too long to log in");
		} else {
			this.myTCPConnection.processReadPackets();
		}
	}

	/**
	 * Disconnects the user with the given reason.
	 */
	public void kickUser(String par1Str) {
		try {
			this.mcServer.getLogAgent().func_98233_a("Disconnecting " + this.getUsernameAndAddress() + ": " + par1Str);
			this.myTCPConnection.addToSendQueue(new Packet255KickDisconnect(par1Str));
			this.myTCPConnection.serverShutdown();
			this.finishedProcessing = true;
		} catch (Exception var3) {
			var3.printStackTrace();
		}
	}

	public void handleClientProtocol(Packet2ClientProtocol par1Packet2ClientProtocol) {
		this.clientUsername = par1Packet2ClientProtocol.getUsername();

		if (!this.clientUsername.equals(StringUtils.stripControlCodes(this.clientUsername))) {
			this.kickUser("Invalid username!");
		} else {
			PublicKey var2 = this.mcServer.getKeyPair().getPublic();

			if (par1Packet2ClientProtocol.getProtocolVersion() != 61) {
				if (par1Packet2ClientProtocol.getProtocolVersion() > 61) {
					this.kickUser("Outdated server!");
				} else {
					this.kickUser("Outdated client!");
				}
			} else {
				this.loginServerId = this.mcServer.isServerInOnlineMode() ? Long.toString(rand.nextLong(), 16) : "-";
				this.verifyToken = new byte[4];
				rand.nextBytes(this.verifyToken);
				this.myTCPConnection
						.addToSendQueue(new Packet253ServerAuthData(this.loginServerId, var2, this.verifyToken));
			}
		}
	}

	public void handleSharedKey(Packet252SharedKey par1Packet252SharedKey) {
		PrivateKey var2 = this.mcServer.getKeyPair().getPrivate();
		this.sharedKey = par1Packet252SharedKey.getSharedKey(var2);

		if (!Arrays.equals(this.verifyToken, par1Packet252SharedKey.getVerifyToken(var2))) {
			this.kickUser("Invalid client reply");
		}

		this.myTCPConnection.addToSendQueue(new Packet252SharedKey());
	}

	public void handleClientCommand(Packet205ClientCommand par1Packet205ClientCommand) {
		if (par1Packet205ClientCommand.forceRespawn == 0) {
			if (this.field_92079_k) {
				this.kickUser("Duplicate login");
				return;
			}

			this.field_92079_k = true;

			if (this.mcServer.isServerInOnlineMode()) {
				(new ThreadLoginVerifier(this)).start();
			} else {
				this.field_72544_i = true;
			}
		}
	}

	public void handleLogin(Packet1Login par1Packet1Login) {
	}

	/**
	 * on success the specified username is connected to the minecraftInstance,
	 * otherwise they are packet255'd
	 */
	public void initializePlayerConnection() {
		String var1 = this.mcServer.getConfigurationManager()
				.allowUserToConnect(this.myTCPConnection.getRemoteAddress(), this.clientUsername);

		if (var1 != null) {
			this.kickUser(var1);
		} else {
			EntityPlayerMP var2 = this.mcServer.getConfigurationManager().createPlayerForUser(this.clientUsername);

			if (var2 != null) {
				this.mcServer.getConfigurationManager().initializeConnectionToPlayer(this.myTCPConnection, var2);
			}
		}

		this.finishedProcessing = true;
	}

	public void handleErrorMessage(String par1Str, Object[] par2ArrayOfObj) {
		this.mcServer.getLogAgent().func_98233_a(this.getUsernameAndAddress() + " lost connection");
		this.finishedProcessing = true;
	}

	/**
	 * Handle a server ping packet.
	 */
	public void handleServerPing(Packet254ServerPing par1Packet254ServerPing) {
		try {
			ServerConfigurationManager var2 = this.mcServer.getConfigurationManager();
			String var3 = null;

			if (par1Packet254ServerPing.readSuccessfully == 1) {
				List var4 = Arrays.asList(new Serializable[] { Integer.valueOf(1), Integer.valueOf(61),
						this.mcServer.getMinecraftVersion(), this.mcServer.getMOTD(),
						Integer.valueOf(var2.getCurrentPlayerCount()), Integer.valueOf(var2.getMaxPlayers()) });
				Object var6;

				for (Iterator var5 = var4.iterator(); var5
						.hasNext(); var3 = var3 + var6.toString().replaceAll("\u0000", "")) {
					var6 = var5.next();

					if (var3 == null) {
						var3 = "\u00a7";
					} else {
						var3 = var3 + "\u0000";
					}
				}
			} else {
				var3 = this.mcServer.getMOTD() + "\u00a7" + var2.getCurrentPlayerCount() + "\u00a7"
						+ var2.getMaxPlayers();
			}

			InetAddress var8 = null;

			if (this.myTCPConnection.getSocket() != null) {
				var8 = this.myTCPConnection.getSocket().getInetAddress();
			}

			this.myTCPConnection.addToSendQueue(new Packet255KickDisconnect(var3));
			this.myTCPConnection.serverShutdown();

			if (var8 != null && this.mcServer.getNetworkThread() instanceof DedicatedServerListenThread) {
				((DedicatedServerListenThread) this.mcServer.getNetworkThread()).func_71761_a(var8);
			}

			this.finishedProcessing = true;
		} catch (Exception var7) {
			var7.printStackTrace();
		}
	}

	/**
	 * Default handler called for packets that don't have their own handlers in
	 * NetServerHandler; kicks player from the server.
	 */
	public void unexpectedPacket(Packet par1Packet) {
		this.kickUser("Protocol error");
	}

	public String getUsernameAndAddress() {
		return this.clientUsername != null
				? this.clientUsername + " [" + this.myTCPConnection.getRemoteAddress().toString() + "]"
				: this.myTCPConnection.getRemoteAddress().toString();
	}

	/**
	 * determine if it is a server handler
	 */
	public boolean isServerHandler() {
		return true;
	}

	/**
	 * Returns the server Id randomly generated by this login handler.
	 */
	static String getServerId(NetLoginHandler par0NetLoginHandler) {
		return par0NetLoginHandler.loginServerId;
	}

	/**
	 * Returns the reference to Minecraft Server.
	 */
	static MinecraftServer getLoginMinecraftServer(NetLoginHandler par0NetLoginHandler) {
		return par0NetLoginHandler.mcServer;
	}

	/**
	 * Return the secret AES sharedKey
	 */
	static SecretKey getSharedKey(NetLoginHandler par0NetLoginHandler) {
		return par0NetLoginHandler.sharedKey;
	}

	/**
	 * Returns the connecting client username.
	 */
	static String getClientUsername(NetLoginHandler par0NetLoginHandler) {
		return par0NetLoginHandler.clientUsername;
	}

	static boolean func_72531_a(NetLoginHandler par0NetLoginHandler, boolean par1) {
		return par0NetLoginHandler.field_72544_i = par1;
	}
}
