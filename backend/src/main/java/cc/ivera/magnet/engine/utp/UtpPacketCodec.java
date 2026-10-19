package cc.ivera.magnet.engine.utp;

import java.io.IOException;
import java.nio.ByteBuffer;

/** BEP29/uTP v1 strict datagram codec, not a reliable byte-stream implementation. */
public final class UtpPacketCodec {
    public static final int HEADER_BYTES=20,MAX_DATAGRAM=1500;
    public static final int DATA=0,FIN=1,STATE=2,RESET=3,SYN=4;
    public record Packet(int type,int connectionId,long timestampMicros,
                         long timestampDeltaMicros,long receiveWindow,
                         int seqNr,int ackNr,byte[] selectiveAck,byte[] payload) {
        public Packet {
            if(type<0 || type>4 || connectionId<0 || connectionId>65535
                    || timestampMicros<0 || timestampMicros>0xffffffffL
                    || timestampDeltaMicros<0 || timestampDeltaMicros>0xffffffffL
                    || receiveWindow<0 || receiveWindow>0xffffffffL
                    || seqNr<0 || seqNr>65535 || ackNr<0 || ackNr>65535
                    || selectiveAck==null || payload==null
                    || (selectiveAck.length>0 && (selectiveAck.length<4
                            || selectiveAck.length%4!=0 || selectiveAck.length>128))
                    || HEADER_BYTES+payload.length+(selectiveAck.length==0?0:2+selectiveAck.length)
                        >MAX_DATAGRAM
                    || (type==DATA && payload.length==0))
                throw new IllegalArgumentException("Invalid uTP packet");
            selectiveAck=selectiveAck.clone();
            payload=payload.clone();
        }
        @Override public byte[] selectiveAck(){return selectiveAck.clone();}
        @Override public byte[] payload(){return payload.clone();}
    }
    public byte[] encode(Packet packet) {
        if(packet==null)throw new IllegalArgumentException("Missing packet");
        var mask=packet.selectiveAck();
        var body=packet.payload();
        var out=ByteBuffer.allocate(HEADER_BYTES+body.length+
                (mask.length==0?0:mask.length+2));
        out.put((byte)((packet.type()<<4)|1));
        out.put((byte)(mask.length==0?0:1));
        out.putShort((short)packet.connectionId());
        out.putInt((int)packet.timestampMicros());
        out.putInt((int)packet.timestampDeltaMicros());
        out.putInt((int)packet.receiveWindow());
        out.putShort((short)packet.seqNr());
        out.putShort((short)packet.ackNr());
        if(mask.length>0){out.put((byte)0);out.put((byte)mask.length);out.put(mask);}
        out.put(body);
        return out.array();
    }
    public Packet decode(byte[] data) throws IOException {
        if(data==null || data.length<HEADER_BYTES || data.length>MAX_DATAGRAM)
            throw new IOException("Malformed uTP datagram size");
        var in=ByteBuffer.wrap(data);
        int head=Byte.toUnsignedInt(in.get());
        int type=head>>>4,version=head&15;
        if(type>4 || version!=1)throw new IOException("Unsupported uTP packet");
        int extension=Byte.toUnsignedInt(in.get());
        int id=Short.toUnsignedInt(in.getShort());
        long stamp=Integer.toUnsignedLong(in.getInt());
        long delta=Integer.toUnsignedLong(in.getInt());
        long window=Integer.toUnsignedLong(in.getInt());
        int seq=Short.toUnsignedInt(in.getShort());
        int ack=Short.toUnsignedInt(in.getShort());
        byte[] sack=new byte[0];
        int count=0;
        while(extension!=0){
            if(++count>8 || in.remaining()<2)
                throw new IOException("Invalid uTP extension chain");
            int next=Byte.toUnsignedInt(in.get());
            int len=Byte.toUnsignedInt(in.get());
            if(len>in.remaining())throw new IOException("Truncated uTP extension");
            if(extension==1) {
                if(sack.length!=0 || len<4 || len>128 || len%4!=0)
                    throw new IOException("Invalid uTP selective ACK");
                sack=new byte[len];
                in.get(sack);
            }else in.position(in.position()+len);
            extension=next;
        }
        var payload=new byte[in.remaining()];
        in.get(payload);
        try {return new Packet(type,id,stamp,delta,window,seq,ack,sack,payload);}
        catch(IllegalArgumentException e){throw new IOException("Invalid uTP packet",e);}
    }
    /** BEP29 selective ACK bit zero represents ack_nr+2 (modulo 2^16). */
    public static boolean selectivelyAcknowledges(byte[] mask,int ackNr,int seqNr) {
        if(mask==null || ackNr<0 || ackNr>65535 || seqNr<0 || seqNr>65535)
            throw new IllegalArgumentException("Invalid selective ACK");
        int offset=(seqNr-ackNr+65536)%65536-2;
        return offset>=0 && offset<mask.length*8 &&
                (mask[offset/8] & (1<<(offset%8)))!=0;
    }
}
