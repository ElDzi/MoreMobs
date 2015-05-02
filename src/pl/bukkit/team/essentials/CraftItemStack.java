package pl.bukkit.team.essentials;

import java.lang.reflect.InvocationTargetException;

import org.bukkit.inventory.ItemStack;

import pl.bukkit.team.protocols.Reflection;
import pl.bukkit.team.protocols.Reflection.*;

public class CraftItemStack {
	
	private Class<Object> craftitemstackClass = Reflection.getUntypedClass("{obc}.inventory.CraftItemStack");
	private Class<Object> itemstackClass = Reflection.getUntypedClass("{nms}.ItemStack");
	private Class<Object> getNBTTagCompound = Reflection.getUntypedClass("{nms}.NBTTagCompound");
	private FieldAccessor<Object> itemstack = Reflection.getField(itemstackClass, getNBTTagCompound, 0);
	
	private Object cis;

	public CraftItemStack(ItemStack is) {
		this.cis = Reflection.getMethod(this.craftitemstackClass, "asNMSCopy", ItemStack.class).invoke(this.craftitemstackClass, is);
	}
	public Object getTag() {
		return this.itemstack.get(this.cis);
	}
	public void setTag(Object tag) {
		this.itemstack.set(this.cis, tag);
	}
	public ItemStack asBukkitCopy() {
		return (ItemStack) Reflection.getMethod(this.craftitemstackClass, "asBukkitCopy", itemstackClass).invoke(this.cis);
	}
}
