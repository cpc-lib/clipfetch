package cc.ivera.magnet.engine.pex;

import cc.ivera.magnet.engine.codec.BencodeDecoder;
import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.tracker.TrackerProtocol;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** BEP11 ut_pex compact peers; incoming messages are untrusted, never connect blindly. */
public final class PeerExchangeCodec {
    private final BencodeDecoder decoder = new BencodeDecoder();
    public record Delta(List<InetSocketAddress> added,List<InetSocketAddress> dropped) {
        public Delta { added=List.copyOf(added); dropped=List.copyOf(dropped); }
    }
    public Delta decode(byte[] bytes) {
        if (bytes == null || bytes.length < 2 || bytes.length > 32 * 1024)
            throw new IllegalArgumentException("Invalid PEX frame length");
        Value value=decoder.decode(bytes);
        if (!(value instanceof DictionaryValue d))
            throw new IllegalArgumentException("PEX payload must be a dictionary");
        List<InetSocketAddress> added=new ArrayList<>();
        List<InetSocketAddress> dropped=new ArrayList<>();
        readPeers(d,"added","added.f",6,added);
        readPeers(d,"added6","added6.f",18,added);
        readPeers(d,"dropped",null,6,dropped);
        readPeers(d,"dropped6",null,18,dropped);
        if (added.isEmpty() && dropped.isEmpty())
            throw new IllegalArgumentException("Empty PEX delta");
        if (added.size()>100 || dropped.size()>100) throw new IllegalArgumentException("Oversized PEX delta");
        return new Delta(added,dropped);
    }
    private static void readPeers(DictionaryValue d,String key,String flagsKey,
                                   int recordBytes,List<InetSocketAddress> output) {
        Value peers=d.get(key);
        if (peers==null) {
            if (flagsKey!=null && d.get(flagsKey)!=null)
                throw new IllegalArgumentException("PEX flags without peers");
            return;
        }
        if (!(peers instanceof BytesValue value)) throw new IllegalArgumentException("Invalid compact PEX peers");
        byte[] compact=value.bytes();
        if (compact.length%recordBytes!=0 || compact.length/recordBytes>100)
            throw new IllegalArgumentException("Invalid PEX compact length");
        if (flagsKey!=null && d.get(flagsKey)!=null) {
            if (!(d.get(flagsKey) instanceof BytesValue flags)
                    || flags.bytes().length!=compact.length/recordBytes)
                throw new IllegalArgumentException("Invalid PEX flags length");
        }
        if (recordBytes==6) {
            output.addAll(TrackerProtocol.compactIpv4(compact));
            return;
        }
        for (int i=0;i<compact.length;i+=18) {
            byte[] ip=java.util.Arrays.copyOfRange(compact,i,i+16);
            int port=((compact[i+16]&255)<<8)|(compact[i+17]&255);
            if(port==0)continue;
            try { output.add(InetSocketAddress.createUnresolved(
                    InetAddress.getByAddress(ip).getHostAddress(),port)); }
            catch (UnknownHostException e) { throw new IllegalArgumentException("Invalid PEX IPv6",e); }
        }
    }
}
