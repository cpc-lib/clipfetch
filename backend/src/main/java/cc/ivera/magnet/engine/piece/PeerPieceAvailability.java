package cc.ivera.magnet.engine.piece;

import java.net.InetSocketAddress;
import java.util.*;

/**
 * Immutable, bounded BEP3 bitfield snapshot for one downloaded torrent.
 * Unknown peers have no asserted presence or absence information.
 * Reported availability is only a scheduling hint; SHA-1 stays authoritative.
 */
public final class PeerPieceAvailability {
    private final int pieces;
    private final Map<InetSocketAddress,BitSet> known;
    private final int[] counts;

    public PeerPieceAvailability(int pieces,Map<InetSocketAddress,BitSet> reported) {
        if(pieces<1 || reported==null || reported.size()>256)
            throw new IllegalArgumentException("Invalid Peer availability snapshot");
        this.pieces=pieces;
        var copy=new LinkedHashMap<InetSocketAddress,BitSet>();
        counts=new int[pieces];
        for(var item:reported.entrySet()) {
            if(item.getKey()==null || item.getValue()==null ||
                    item.getValue().length()>pieces)
                throw new IllegalArgumentException("Invalid bitfield");
            BitSet bits=(BitSet)item.getValue().clone();
            copy.put(item.getKey(),bits);
            for(int bit=bits.nextSetBit(0);bit>=0;bit=bits.nextSetBit(bit+1))
                counts[bit]++;
        }
        known=Collections.unmodifiableMap(copy);
    }

    public int pieceCount() {return pieces;}
    public boolean hasReports() {return !known.isEmpty();}
    public int reports() {return known.size();}
    public int knownAvailability(int piece) {
        if(piece<0 || piece>=pieces)throw new IllegalArgumentException("Invalid Piece");
        return counts[piece];
    }
    /** A known-false bitfield must not be treated as an available Piece. */
    public boolean couldHave(InetSocketAddress peer,int piece) {
        if(piece<0 || piece>=pieces)throw new IllegalArgumentException("Invalid Piece");
        BitSet bits=known.get(peer);
        return bits==null || bits.get(piece);
    }
    public boolean confirmed(InetSocketAddress peer,int piece) {
        if(piece<0 || piece>=pieces)throw new IllegalArgumentException("Invalid Piece");
        var bits=known.get(peer);
        return bits!=null && bits.get(piece);
    }
    /** Rare confirmed pieces first; zero-availability pieces last; stable tie by index. */
    public List<Integer> choose(BitSet wanted,BitSet verified,int limit) {
        if(wanted==null || verified==null || limit<1 || limit>256 ||
                wanted.length()>pieces || verified.length()>pieces)
            throw new IllegalArgumentException("Invalid Piece selection");
        var indexes=new ArrayList<Integer>();
        for(int piece=wanted.nextSetBit(0);piece>=0;piece=wanted.nextSetBit(piece+1))
            if(!verified.get(piece))indexes.add(piece);
        if(hasReports())indexes.sort(Comparator
                .comparingInt((Integer p)->counts[p]==0?Integer.MAX_VALUE:counts[p])
                .thenComparingInt(Integer::intValue));
        if(indexes.size()>limit)return List.copyOf(indexes.subList(0,limit));
        return List.copyOf(indexes);
    }
}
