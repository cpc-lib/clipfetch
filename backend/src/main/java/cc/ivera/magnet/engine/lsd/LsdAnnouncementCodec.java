package cc.ivera.magnet.engine.lsd;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/** BEP14 Local Service Discovery messages. Never accept advertised Host as endpoint. */
public final class LsdAnnouncementCodec {
    public static final String MULTICAST_IP="239.192.152.143";
    public static final int MULTICAST_PORT=6771;
    private static final int MAX_PACKET=1400;

    public byte[] encode(byte[] hash,int listenPort,String cookie) {
        if(hash==null || hash.length!=20 || listenPort<1 || listenPort>65535
                || cookie==null || !cookie.matches("[0-9a-fA-F]{16,40}"))
            throw new IllegalArgumentException("Invalid LSD announce");
        String text="BT-SEARCH * HTTP/1.1\r\n"
                +"Host: "+MULTICAST_IP+":"+MULTICAST_PORT+"\r\n"
                +"Port: "+listenPort+"\r\n"
                +"Infohash: "+HexFormat.of().formatHex(hash)+"\r\n"
                +"cookie: "+cookie+"\r\n\r\n";
        return text.getBytes(StandardCharsets.US_ASCII);
    }
    public Optional<InetSocketAddress> parse(byte[] bytes,InetAddress sender,byte[] expectedHash,
                                             String ownCookie) {
        if(bytes==null || bytes.length<32 || bytes.length>MAX_PACKET
                || !(sender instanceof Inet4Address) || expectedHash==null || expectedHash.length!=20)
            return Optional.empty();
        // LSD must remain LAN-only. Never accept a public remote address.
        if(!sender.isSiteLocalAddress() && !sender.isLinkLocalAddress())return Optional.empty();
        String message=new String(bytes,StandardCharsets.US_ASCII);
        if(!message.startsWith("BT-SEARCH * HTTP/1.1\r\n") || !message.contains("\r\n\r\n"))
            return Optional.empty();
        String[] rows=message.substring(0,message.indexOf("\r\n\r\n")).split("\r\n");
        String hash=HexFormat.of().formatHex(expectedHash);
        Integer port=null;
        boolean match=false;
        String cookie=null;
        for(int i=1;i<rows.length;i++) {
            int colon=rows[i].indexOf(':');if(colon<=0)continue;
            String key=rows[i].substring(0,colon).trim().toLowerCase(Locale.ROOT);
            String val=rows[i].substring(colon+1).trim();
            if(key.equals("port")){
                if(port!=null)return Optional.empty();
                try { port=Integer.parseInt(val); }catch(NumberFormatException e){return Optional.empty();}
            }else if(key.equals("infohash")){if(val.equalsIgnoreCase(hash))match=true;
            }else if(key.equals("cookie")){cookie=val;}
        }
        if(!match || port==null || port<1 || port>65535 || (cookie!=null && cookie.equals(ownCookie)))
            return Optional.empty();
        return Optional.of(new InetSocketAddress(sender,port));
    }
}
