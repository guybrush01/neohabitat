package org.made.neohabitat.mods;

import java.util.Hashtable;

import org.elkoserver.foundation.json.JSONMethod;
import org.elkoserver.foundation.json.OptBoolean;
import org.elkoserver.foundation.json.OptInteger;
import org.elkoserver.foundation.json.OptString;
import org.elkoserver.json.EncodeControl;
import org.elkoserver.json.JSONLiteral;
import org.elkoserver.server.context.Context;
import org.elkoserver.server.context.ContextMod;
import org.elkoserver.server.context.User;
import org.elkoserver.server.context.UserWatcher;
import org.made.neohabitat.Constants;
import org.made.neohabitat.Container;
import org.made.neohabitat.HabitatMod;



/**
 * Habitat Region Mod (attached to a Elko Context)
 * 
 * The Region has all the state and behaviors for the main object of Habitat. It
 * is the "room" logic, controlling how things interact with each other - not on
 * a Item to Item or User to User basis, but when interacting between multiple
 * objects in the region.
 * 
 * @author randy
 *
 */

public class Region extends Container implements UserWatcher, ContextMod, Constants {
    
	public static String	MOTD = "Welcome to Habitat!";
	
	/** Static flag on if new features should be activated. Anyone can toggle with //neohabitat */
	public static boolean	NEOHABITAT_FEATURES = true;	
	
	/** The number of tokens to give to an avatar each new day that the user loggs in */
	public static final int	STIPEND = 100;
	
    /** The default depth for a region. */
    public static final int DEFAULT_REGION_DEPTH = 32;

    /** The default maximum number of avatars for a Region. */
    public static final int DEFAULT_MAX_AVATARS = 6;
    
    /** Statics are shared amongst all regions */

    /** All the currently logged in user names for ESP lookup */
    public static Hashtable<String, User> NameToUser = new Hashtable<String, User>();    

    public int HabitatClass() {
        return CLASS_REGION;
    }
    
    public String HabitatModName() {
        return "Region";
    }
    
    public int capacity() {
        return 255;
    }
    
    public int pc_state_bytes() {
        return 1;
    };
    
    public boolean known() {
        return true;
    }
    
    public boolean opaque_container() {
        return false;
    }
    
    public boolean filler() {
        return false;
    }
    
    /** A collection of server-side region status flags */
    public boolean    nitty_bits[] = new boolean[32];
    /** The lighting level in the room. 0 is Dark. */
    public int        lighting     = 1;
    /** The horizon line for the region to clip avatar motion */
    public int        depth        = DEFAULT_REGION_DEPTH;
    /** The maximum number of Avatars that can be in this Region */
    public int        max_avatars  = DEFAULT_MAX_AVATARS;

    /**
     * This is an array holding all the Mods for all the Users and Items in this
     * room.
     */
    public HabitatMod noids[]      = new HabitatMod[256];
    public int		  nextNoid	   = 1;
    public String     neighbors[]  = { "", "", "", "" };
    /** Direction to nearest Town */
    public String	  town_dir     = "";
    /** Direciton to nearest Teleport Booth */
    public String	  port_dir     = "";    
    
    @JSONMethod({ "style", "x", "y", "orientation", "gr_state", "nitty_bits", "depth", "lighting",
        "town_dir", "port_dir", "max_avatars", "neighbors" })
    public Region(OptInteger style, OptInteger x, OptInteger y, OptInteger orientation, OptInteger gr_state,
        OptInteger nitty_bits, OptInteger depth, OptInteger lighting,
        OptString town_dir, OptString port_dir, OptInteger max_avatars,
        String[] neighbors) {
        super(style, x, y, orientation, gr_state, new OptBoolean(false));
        if (nitty_bits.value(-1) != -1) {
            this.nitty_bits = unpackBits(nitty_bits.value());
        }
        this.depth = depth.value(DEFAULT_REGION_DEPTH);
        this.lighting = lighting.value(1);
        this.max_avatars = max_avatars.value(DEFAULT_MAX_AVATARS);
        this.neighbors = neighbors;
        this.town_dir = town_dir.value("");
        this.port_dir = port_dir.value("");
    }

    public Region(int style, int x, int y, int orientation, int gr_state, boolean[] nitty_bits, int depth, int lighting,
        String town_dir, String port_dir, int max_avatars, String[] neighbors) {
        super(style, x, y, orientation, gr_state, false);
        this.nitty_bits = nitty_bits;
        this.depth = depth;
        this.lighting = lighting;
        this.max_avatars = max_avatars;
        this.neighbors = neighbors;
        this.town_dir = town_dir;
        this.port_dir = port_dir;
    }

    @Override
    public void objectIsComplete() {
        ((Context) this.object()).registerUserWatcher((UserWatcher) this);
        this.noids[0] = this;
    }

    public void noteUserArrival(User who) {
    	Avatar avatar = (Avatar) who.getMod(Avatar.class);
    	avatar.inc_record(HS$travel);
    	int today = (int) (System.currentTimeMillis() / ONE_DAY);
    	int time  = (int) (System.currentTimeMillis() % ONE_DAY);
    	if (today > avatar.lastConnectedDay || time - avatar.lastConnectedTime > 1000 * 60 * 2) {
    		if (NEOHABITAT_FEATURES) {
    			tellEveryone(who.name() + " has arrived.");
    		}
    		avatar.firstConnection = true;
    	}
    	if (today > avatar.lastConnectedDay) {
    		avatar.bankBalance += STIPEND;
            avatar.set_record(HS$wealth, avatar.bankBalance);
    		avatar.inc_record(HS$lifetime);
    	}
		avatar.lastConnectedDay  = today;
    	avatar.lastConnectedTime = time;
    	Region.addUser(who);
    }
    
    public void noteUserDeparture(User who) {
    	Avatar avatar = avatar(who);
        if (avatar.holding_restricted_object()) {
        	avatar.heldObject().putMeBack(who, false);
        }
    	avatar.lastConnectedDay  = (int) (System.currentTimeMillis() / ONE_DAY);
    	avatar.lastConnectedTime = (int) (System.currentTimeMillis() % ONE_DAY);
		avatar.gen_flags[MODIFIED] = true;    					
    	avatar.checkpoint_object(avatar);
    	Region.removeUser(who);
    }
        
    public static void addUser(User from) {
    	NameToUser.put(from.name().toLowerCase(), from);
    }
    
    public static void removeUser(User from) {
    	Avatar avatar = (Avatar) from.getMod(Avatar.class);
    	NameToUser.remove(from.name().toLowerCase());
    	removeContentsFromRegion(avatar);
    	removeFromObjList(avatar);
    }
    
    public static User getUserByName(String name) {
    	if (name != null) {    		
        	return (User) NameToUser.get(name.toLowerCase());
    	}
    	return null;
    }
    
    @Override
    public JSONLiteral encode(EncodeControl control) {
        JSONLiteral result = new JSONLiteral(HabitatModName(), control); // Normally I would call encodeCommon() but don't want to bother with the extraneous fields.
        result.addParameter("orientation", orientation);
        if (packBits(nitty_bits) != 0) {
            result.addParameter("nitty_bits", packBits(nitty_bits));
        }
        result.addParameter("depth", depth);
        result.addParameter("lighting", lighting);
        result.addParameter("neighbors", neighbors);
        if (control.toRepository()) {
            result.addParameter("max_avatars", max_avatars);
        	result.addParameter("town_dir", town_dir);
        	result.addParameter("port_dir", port_dir);
        }
        result.finish();
        return result;
    }
    
    private static int incNoid(int noid) {
    	noid++;
    	switch (noid) {
    		case THE_REGION:
    		case GHOST_NOID:
    		case 256:
	    		return 1;
	    }
    	return noid;
    }
    
    /**
     * Add a HabitatMod to the object list for easy lookup by noid.
     * This method uses a monotonically increasing index, remembering
     * the previous noid assignment. Deleted objects will leave holes
     * that we are in no hurry to fill in. Note the reserved NOID ids
     * of THE_REGION (0) and GHOST_NOID (255).
     * 
     * @param mod
     */
    public static boolean addToNoids(HabitatMod mod) {
    	Region			region	= mod.current_region();
        int				noid	= region.nextNoid;
        HabitatMod[] 	noids	= region.noids;
        
        if (region.nextNoid == -1) {
        	return false; /* Too many things in this region!*/
        }

        noids[noid] 	= mod;
        mod.noid 		= noid;
        
        noid = incNoid(noid);
        
        while (null != noids[noid]) {
        	noid = incNoid(noid);
        	if (noid == region.nextNoid) {
        		noid = -1;
        		break;	// Searched everything, none free.
        	}
        }
        region.nextNoid = noid;
        return true; // noid added, even if no
    }
    
    /**
     * Remove the noid from the object list.
     * 
     * @param mod
     *            The object to remove from the noid list.
     */
    public static void removeFromObjList(HabitatMod mod) {
        mod.current_region().noids[mod.noid] = null;
    }
        
    /**
     * When items go away because their container left or closed, we must reclaim scarece resources: noids and 
     * 
     * @param cont
     */
    public static void removeContentsFromRegion(Container cont) {
    	for (int i = 0; i < cont.capacity(); i++) {
    		HabitatMod mod = cont.contents(i);
    		if (mod != null) {
    			removeFromObjList(mod);
    		}
    	}    	
    }
    
    public static void tellEveryone(String text) {
    	for (String key: NameToUser.keySet()) {
    		User	user	= NameToUser.get(key);
    		Avatar	avatar	= (Avatar) user.getMod(Avatar.class);
    		avatar.object_say(user, UPGRADE_PREFIX + text);
        }
    }
    
    /**
     * The client is leaving the Habitat Application and wants to politely
     * disconnect.
     * 
     * @param from
     *            The client disconnecting
     */
    @JSONMethod({ "reason" })
    public void LEAVE(User from, OptInteger reason) {
        if (reason.present()) {
            trace_msg("Client error " + CLIENT_ERRORS[reason.value()] + " reported by " + from.ref());
        }
        try {
            current_region().context().exit(from);
        } catch (Exception ignored) {
            trace_msg("Invalid attempt to leave by " + from.ref() + " failed: " + ignored.toString());
        }
    }
    
    /**
     * The client is slow and this might provide an advantage to others seeing a
     * new avatar before it can react. The server is told by this message that
     * it deal with messages before this as being before the user has appeared.
     * 
     * @param from
     *            The client connection that needs to catch up...
     */
    @JSONMethod
    public void FINGER_IN_QUE(User from) {
        this.send_private_msg(from, 0, from, "CAUGHT_UP_$", "err", TRUE);
    }
    
    /**
     * Handle the client request to "appear" after the client is done loading
     * the region.
     * 
     * @param from
     *            The client connection that has "caught up" loading the
     *            contents vector it just received.
     */ 
    @JSONMethod
    public void I_AM_HERE(User from) {
    	Avatar who = avatar(from);
    	who.gr_state &= ~INVISIBLE;
    	who.showDebugInfo(from);
    	send_broadcast_msg(0, "APPEARING_$", "appearing", who.noid);
    	if (who.firstConnection) {
    		object_say(from, MOTD);
    		if (NEOHABITAT_FEATURES) {
    			if (NameToUser.size() < 2) {
    				object_say(from, UPGRADE_PREFIX + "You are the only Avatar here right now.");
    			} else {
    				object_say(from, UPGRADE_PREFIX + "There are " + (NameToUser.size() - 1) + " other Avatars here. Press F3 to see a list.");
    			}
    		}
    	}
    	who.firstConnection = false;
    }
    
    /**
     * Handle request to change the Message of the Day.
     * Presently, this is a non-persistent change.
     * 
     * @param from
     * @param MOTD
     */
    @JSONMethod ({ "MOTD" })
    public void SET_MOTD(User from, String MOTD) {
    	// TODO FRF Security is missing from this feature. Should this be a message on Admin/Session?
    	Region.MOTD = MOTD;
    }
    
    /**
     * Handle request to send a word balloon message
     * to every user currently online.
     * 
     * @param from
     * @param MOTD
     */
    @JSONMethod ({ "text" })
    public void TELL_EVERYONE(User from, String text) {
    	// TODO FRF Security is missing from this feature. Should this be a message on Admin/Session?
    	tellEveryone(" From: The Oracle   To: All Avatars: ");
    	tellEveryone(text);
    }
    
    /**
     * Handle a prompted message, overloading the text-entry field. This
     * requires some callback related storage in Avatar.
     * 
     * @param from
     *            The user-connection that sent the prompt reply
     * @param text
     *            The prompt reply (includes any prompt.)
     */
    @JSONMethod({ "text" })
    public void PROMPT_REPLY(User from, OptString text) {
        String prompt = text.value("");
        String body = null;
        Avatar avatar = avatar(from);
        if (prompt.contains(GOD_TOOL_PROMPT)) {
            body = prompt.substring(GOD_TOOL_PROMPT.length());
            if (0 == body.length()) {
                avatar.savedMagical = null;
                avatar.savedTarget = null;
                return;
            }
            avatar.savedMagical.god_tool_revisited(from, body);
            return;
        }
    }

    public void describeRegion(User from, int noid) {
	     String name_str = object().name();
	     String help_str = "";
	     if (name_str.isEmpty()) 
	          help_str = "This region has no name";
	     else
	          help_str = "This region is " + name_str;
	     
	     if (!town_dir.isEmpty()) 
	          help_str += ".  The nearest town is " + town_dir;
	               
	     if (!port_dir.isEmpty()) 
	          help_str += ".  The nearest teleport booth is " + port_dir;
	     
	     help_str += ".";
	        send_reply_msg(from, noid, "text", help_str);
    }
}