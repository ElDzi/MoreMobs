package pl.bukkit.team.packets;

import pl.bukkit.team.Main;
import pl.bukkit.team.protocols.Reflection;
import pl.bukkit.team.protocols.TinyProtocol;

public class PacketPlayOutPlayerInfo extends AbstractPacket {
	
	private static final Object handle = Reflection.getConstructor("{nms}.PacketPlayOutPlayerInfo").invoke();
	
	public PacketPlayOutPlayerInfo() { super(handle, Main.getTinyProtocol());}
    
    public PacketPlayOutPlayerInfo(Object packet,TinyProtocol tinyprotocol) { super(packet, tinyprotocol); }

	
    public String getPlayerName() { return (String) Reflection.getMethod(packet.getClass(), "a").invoke(packet); }
    
    public void setPlayerName(String value) { Reflection.getField(packet.getClass(), "a", String.class).set(packet, value); }
    
    public boolean getOnline() { return (boolean) Reflection.getMethod(packet.getClass(), "b").invoke(packet); }
    
    public void setOnline(boolean value) { Reflection.getField(packet.getClass(), "b", boolean.class).set(packet, value); }
    
    public int getPing() { return (int) Reflection.getMethod(packet.getClass(), "c").invoke(packet); }
    
    public void setPing(int value) { Reflection.getField(packet.getClass(), "c", int.class).set(packet, value); }
    
    public void setPacket(String value1, boolean value2, int value3) {setPlayerName(value1); setOnline(value2); setPing(value3);}

}
