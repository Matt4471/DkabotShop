package com.dkabot.DkabotShop;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.persistence.PersistenceException;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import com.dkabot.Metrics.Metrics;

public class DkabotShop extends JavaPlugin {
	public static DkabotShop plugin;
	Logger log = Logger.getLogger("Minecraft");
	private Sellers Sell;
	private Buyers Buy;
	private History Hist;
	private ShopInfo Info;
	public ItemDb itemDB = null;
	public static Vault vault = null;
	public Economy economy = null;
	
	@Override
	public void onEnable() {
		//Vault dependency checker
        Plugin x = this.getServer().getPluginManager().getPlugin("Vault");
        if(x != null & x instanceof Vault) {
            vault = (Vault) x;
            log.info(String.format("[%s] Hooked %s %s", getDescription().getName(), vault.getDescription().getName(), vault.getDescription().getVersion()));
        } else {
            log.severe(String.format("Vault dependency not found! Disabling..."));
            getPluginLoader().disablePlugin(this);
           return;
        }
		if(!setupEconomy()) {
			log.severe("No economy system found. You need one to use this!");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}
		//Sets up ItemDb
		itemDB = new ItemDb(this);
		itemDB.onReload();
		//Default configuration in case values are missing
		defaultConfig();
        //Configuration Validator
		List<String> result = validateConfig();
		if(result == null) getServer().getPluginManager().disablePlugin(this);
		if(!result.isEmpty()) {
			log.severe("[DkabotShop] Error(s) in configuration!");
			for(int i = 0; i < result.size();) {
				String error = result.get(i).split(",")[0];
				String area = result.get(i).split(",")[1];
				log.severe("[DkabotShop] Error on " + error + " in the " + area + " section!");
				i++;
			}
			log.severe("[DkabotShop] Disabling due to above errors...");
			getServer().getPluginManager().disablePlugin(this);
		}
        //The rest of onEnable()
		setupDatabase();
		Sell = new Sellers(this);
		Buy = new Buyers(this);
		Hist = new History(this);
		Info = new ShopInfo(this);
		getCommand("buy").setExecutor(Buy);
		getCommand("stock").setExecutor(Buy);
		getCommand("sell").setExecutor(Sell);
		getCommand("cancel").setExecutor(Sell);
		getCommand("price").setExecutor(Sell);
		getCommand("sales").setExecutor(Hist);
		getCommand("shopinfo").setExecutor(Info);
		
		//Plugin Metrics
		try {
		    Metrics metrics = new Metrics(this);
		    metrics.start();
		}
		catch (IOException ex) {
			log.warning("[DkabotShop] Failed to start plugin metrics :(");
		}
		
		log.info(getDescription().getName() + " version " + getDescription().getVersion() + " is now enabled,");
		reloadConfig();
	}
	
	@Override
	public void onDisable() {
		log.info(getDescription().getName() + " is now disabled.");
	}
	    
	boolean isInt(String s) {
		try {
			Integer.parseInt(s);
				return true;
		    }
		    catch(NumberFormatException nfe) {
		    	return false;
		    }
	    }
	
	boolean isDouble(String s) {
		try {
			Double.parseDouble(s);
				return true;
		    }
		    catch(NumberFormatException nfe) {
		    	return false;
		    }
	    }
	    
	    public void setupDatabase() {
	        try {
	            getDatabase().find(DB_ForSale.class).findRowCount();
	            getDatabase().find(DB_History.class).findRowCount();
	        } catch (PersistenceException ex) {
	            log.info("Installing database due to first time usage");
	            installDDL();
	        }
	    }
	    
	    @Override
	    public ArrayList<Class<?>> getDatabaseClasses() {
	        ArrayList<Class<?>> list = new ArrayList<Class<?>>();
	        list.add(DB_ForSale.class);
	        list.add(DB_History.class);
	        return list;
	    }
		
		boolean isDecimal(double v) {
	      return (Math.floor(v) != v);
	      //If true, decimal, else whole number.
		}
		
		private Boolean setupEconomy() {
	        RegisteredServiceProvider<Economy> economyProvider = getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
	        if (economyProvider != null) {
	            economy = economyProvider.getProvider();
	        }
	        return (economy != null);
	    }
		
		ItemStack getMaterial(String itemString, boolean allowHand, Player player) {
			return getMaterial(itemString, allowHand, player, true);
		}
		
		ItemStack getMaterial(String itemString, boolean allowHand, Player player, boolean useAlias) {
			Material material = null;
			String materialString = itemString.split(":")[0];
			Short dataValue = null;
			if(itemString.split(":").length > 1) {
				try {
					dataValue = Short.parseShort(itemString.split(":")[1]);
				}
				catch(NumberFormatException nfe) {
					dataValue = 0;
				}
			}
			if(useAlias) {
				//Aliases, always first
				for(String alias : getConfig().getStringList("ItemAlias")) {
					if(!materialString.equalsIgnoreCase(alias.split(",")[0])) continue;
					String actualMaterial = alias.split(",")[1];
					//In case of an item ID
					if(isInt(actualMaterial)) material = Material.getMaterial(Integer.parseInt(actualMaterial));
					//Must be a material name
					else {
						material = Material.getMaterial(actualMaterial.toUpperCase());
						if(material == null) {
							ItemStack stack = itemDB.get(actualMaterial);
							if(stack == null) return stack;
							material = stack.getType();
							if(dataValue == null) dataValue = stack.getDurability();
						}
					}
					//Should be an actual material
					if(dataValue == null) dataValue = 0;
					if(material == Material.AIR) return null;
					return new ItemStack(material, 1, dataValue);
				}
			}
			//"hand" as an item, can be overridden by an alias
			if(materialString.equalsIgnoreCase("hand")) {
				if(allowHand) {
					material = player.getItemInHand().getType();
					dataValue = player.getItemInHand().getDurability();
				}
				else return null; //if hand is not allowed and it's not an alias, not bothering
			}
			//if it's an item ID, that's all we need
			else if(isInt(materialString)) {
				material = Material.getMaterial(Integer.parseInt(materialString));
			}
			//if it's not, more effort.
			else {
				//try as an items.csv name
				ItemStack stack = itemDB.get(materialString);
				if(stack != null) {
					material = stack.getType();
					if(dataValue == null) dataValue = stack.getDurability();
				}
				//items.csv didn't work... try material name?
				else {
					material = Material.getMaterial(materialString.toUpperCase());
					if(material == null) return null;
				}
			}
			if(dataValue == null) dataValue = 0;
			if(material == Material.AIR) return null;
			//could return null or not
			return new ItemStack(material, 1, dataValue);
	}
	
		Double getMoney(String s) {
			try {
				Double d = Double.parseDouble(s);
				DecimalFormat twoDForm = new DecimalFormat("#.00");
				return Double.parseDouble(twoDForm.format(d));
			}
			catch(NumberFormatException e) {
				return null;
			}
		}
		
		Double getMoneyPlayer(String s, Player player) {
			Double price = getMoney(s);
			if(price == null) {
				player.sendMessage(ChatColor.RED + "Invalid cost amount!");
				return null;
			}
			if(price <= 0) {
				player.sendMessage(ChatColor.RED + "The cost cannot be 0 or negative!");
				return null;
			}
			if(!player.hasPermission("dkabotshopadmin.bypassMaxPrice")) {
				Double maxPrice = getConfig().getDouble("MaxPrice");
				if(maxPrice != -1 && price > maxPrice) {
					player.sendMessage(ChatColor.RED + "The cost cannot be above " + maxPrice.toString() + "!");
					return null;
				}
			}
			if(!player.hasPermission("dkabotshopadmin.bypassMinPrice")) {
				Double minPrice = getConfig().getDouble("MinPrice");
				if(minPrice != -1 && price < minPrice) {
					player.sendMessage(ChatColor.RED + "The cost cannot be below " + minPrice.toString() + "!");
					return null;
				}
			}
			return price;
		}
		
		//sets the default config
		private void defaultConfig() {
			//Create and set string lists
			List<String> blacklistAlways = new ArrayList<String>();
			List<String> itemAlias = new ArrayList<String>();
			blacklistAlways.add("137");
			itemAlias.add("wood,17:0");
			//Add default config and save
			getConfig().addDefault("Blacklist.Always", blacklistAlways);
			getConfig().addDefault("ItemAlias", itemAlias);
			getConfig().addDefault("DisableBroadcasting", false);
			getConfig().addDefault("AlternateBroadcasting", false);
			getConfig().addDefault("AlwaysBuyAvailable", false);
			getConfig().addDefault("MaxStock", -1);
			getConfig().addDefault("MaxPrice", (double)-1);
			getConfig().addDefault("MinPrice", (double)-1);
			//default messages
			getConfig().addDefault("Messages.NewlySelling", "&6[player]&9 is now selling&6 [amount] [item]&9 for &6 [cost] [currency]&9 each.");
			getConfig().addDefault("Messages.Added", "&6[player]&9 has added&6 [amount]&9 more&6 [item]&9 to their shop.");
			getConfig().addDefault("Messages.AddedPriceChange", "&6[player]&9 has added&6 [amount]&9 more&6 [item]&9 to their shop and changed it's price to&6 [cost] [currency]&9.");
			getConfig().addDefault("Messages.RemovedSome", "&6[player]&9 has reduced their shop's supply of&6 [item]&9 to&6 [amount]&9.");
			getConfig().addDefault("Messages.RemovedAll", "&6[player]&9 has removed their&6 [item]&9 from their shop.");
			getConfig().addDefault("Messages.PriceChange", "&6[player]&9 has changed their shop's price of&6 [item]&9 to&6 [cost] [currency]&9.");
			getConfig().addDefault("Messages.ShopBoughtSome", "&6[player]&9 bought &6[amount]&9 of your shop's &6[item]&9!");
			getConfig().addDefault("Messages.ShopBoughtAll", "&6[player]&9 bought &6all&9 your shop's &6[item]&9!");
			//save that info
			getConfig().options().copyDefaults(true);
			saveConfig();
		}
		
		//Validates the config, as the function name suggests
		private List<String> validateConfig() {
			try {
				List<String> itemsWrong = new ArrayList<String>();
				for(String str : getConfig().getStringList("ItemAlias")) {
					if(str.split(",").length != 2) {
						itemsWrong.add("formatting,ItemAlias");
						continue;
					}
					String materialString = str.split(",")[1];
					if(!materialString.equalsIgnoreCase("hand") && getMaterial(materialString, false, null, false) == null) itemsWrong.add(materialString + ",ItemAlias");
				}
				for(String materialString : getConfig().getStringList("Blacklist.Always")) {
					if(getMaterial(materialString, false, null, false) == null) itemsWrong.add(materialString + ",Blacklist Always");
				}
				if(getConfig().getDouble("MaxPrice") <= 0 && getConfig().getDouble("MaxPrice") != -1) itemsWrong.add(getConfig().getDouble("MaxPrice") + ",Maximum Price");
				if(getConfig().getDouble("MinPrice") <= 0 && getConfig().getDouble("MinPrice") != -1) itemsWrong.add(getConfig().getDouble("MinPrice") + ",Minimum Price");
				if(getConfig().getInt("MaxStock") <= 0 && getConfig().getInt("MaxStock") != -1) itemsWrong.add(getConfig().getInt("MaxStock") + ",Max Stock");
				return itemsWrong;
			}
			catch (Exception e) {
				log.severe("[DkabotShop] Exception occurred while processing the configuration! Printing stacktrace and disabling...");
				e.printStackTrace();
				return null;
			}
		}
		
		//checks if an item is on a blacklist. Boolean for now, but will become something else once a datavalue item blacklist is added
		boolean illegalItem(ItemStack material) {
			for(String materialString : getConfig().getStringList("Blacklist.Always")) {
				ItemStack blackMaterial = getMaterial(materialString, false, null, false);
				if(blackMaterial.getTypeId() == material.getTypeId() && blackMaterial.getDurability() == material.getDurability()) return true;
			}		
			return false;
		}

		//function to give items, split into itemstacks based on item.getMaxStackSize()
		Integer giveItem(ItemStack item, Player player) {
			Integer fullItemStacks = item.getAmount() / item.getMaxStackSize();
			Integer fullItemStacksRemaining = fullItemStacks;
			Integer nonFullItemStack = item.getAmount() % item.getMaxStackSize();
			Integer amountNotReturned = 0;
			Integer notReturnedAsInt = 0;
			for(int i = 0; i < fullItemStacks;) {
				HashMap<Integer, ItemStack> notReturned = player.getInventory().addItem(new ItemStack(item.getType(), item.getMaxStackSize(), item.getDurability()));
				fullItemStacksRemaining--;
				if(notReturned.isEmpty()) i++;
				else {
					for(int j = 0; j < notReturned.size();) {
						notReturnedAsInt = notReturnedAsInt + notReturned.get(j).getAmount();
						j++;
					}
					break;
				}
			}
			if(notReturnedAsInt != 0) notReturnedAsInt = notReturnedAsInt + nonFullItemStack;
			else if (nonFullItemStack != 0) {
				HashMap<Integer, ItemStack> notReturned = player.getInventory().addItem(new ItemStack(item.getType(), nonFullItemStack, item.getDurability()));
				for(int i = 0; i < notReturned.size();) {
					notReturnedAsInt = notReturnedAsInt + notReturned.get(i).getAmount();
					i++;
				}
			}
			amountNotReturned = amountNotReturned + (fullItemStacksRemaining * item.getMaxStackSize()) + notReturnedAsInt;
			return amountNotReturned;		
		}
		
		//broadcasts messages
		void broadcastMessage(String message) {
			//In case broadcasting is disabled, just exit now
			if(getConfig().getBoolean("DisableBroadcasting")) return;
			//In case alternate broadcasting is enabled, send the message to every player
			if(getConfig().getBoolean("AlternateBroadcasting")) {
				for (Player player : getServer().getOnlinePlayers()) player.sendMessage(message);
			}
			//In case alternate broadcasting is disabled (default), make the server send the message
			else getServer().broadcastMessage(message);
		}
		
		//formats messages for broadcast
		String formatMessage(String messagePointer, String player, String item, Integer amount, Double cost, String currencyName) {
			if(messagePointer == null) return "Failure to Generate Message";
			String message = getConfig().getString("Messages." + messagePointer, null);
			if(message == null) return "Failure to Generate Message";
			
			Map<String, Object> replacements = new HashMap<String, Object>();
			replacements.put("[player]", player);
			replacements.put("[item]", item);
			replacements.put("[amount]", amount);
			replacements.put("[cost]", cost);
		    replacements.put("[currency]", currencyName);
		    
		    for(String replace : replacements.keySet()) {
		    	String replacement;
		    	if(replacements.get(replace) == null) replacement = "NULL";
		    	else if(replacements.get(replace) instanceof String) replacement = (String) replacements.get(replace);
		    	else replacement = replacements.get(replace).toString();
		    	message = message.replace(replace, replacement);
		    }
		    
			for(ChatColor color : ChatColor.values()) {
				message = message.replace("&" + color.getChar(), color.toString());
			}
			
			return message;
		}
		//Same as bukkit's all, but needs an inventory argument and ignores the amount in the stack
	    public HashMap<Integer, ItemStack> all(Inventory inv, ItemStack stack) {
	        HashMap<Integer, ItemStack> slots = new HashMap<Integer, ItemStack>();

	        ItemStack[] inventory = inv.getContents();
	        for (int i = 0; i < inventory.length; i++) {
	            ItemStack item = inventory[i];
	            if (item != null && item.getTypeId() == stack.getTypeId() && item.getDurability() == stack.getDurability()) {
	                slots.put(i, item);
	            }
	        }
	        return slots;
	    }
	    
	    //Same as bukkit's contains, but needs an inventory argument and ignores the amount in the stack in favor of the amount argument
	    public boolean contains(Inventory inv, ItemStack stack, int amount) {
	        int amt = 0;
	        for (ItemStack item : inv.getContents()) {
	            if (item != null && item.getTypeId() == stack.getTypeId() && item.getDurability() == stack.getDurability()) {
	                amt += item.getAmount();
	            }
	        }
	        return amt >= amount;
	    }
}