package pl.bukkit.team.packets;

import org.bukkit.entity.Player;

import pl.bukkit.team.protocols.TinyProtocol;

public abstract class AbstractPacket {
	
	protected Object packet;
	protected TinyProtocol tinyprotocol;

	protected AbstractPacket(Object packet, TinyProtocol tinyprotocol) {
		if (packet == null) throw new IllegalArgumentException("Packet handle cannot be NULL.");
		if(tinyprotocol == null) throw new IllegalArgumentException("TinyProtocol cannot be NULL.");
		
		this.packet = packet;
		this.tinyprotocol = tinyprotocol;
	}

	public Object getHandle() { return this.packet; }
	
	public void sendPacket(Player receiver) { tinyprotocol.sendPacket(receiver, getHandle()); }
	
	public void recievePacket(Player sender) { tinyprotocol.receivePacket(sender, getHandle()); }
}
