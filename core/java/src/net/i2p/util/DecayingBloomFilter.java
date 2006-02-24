package net.i2p.util;

import org.xlattice.crypto.filters.BloomSHA1;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

import java.util.Random;

/**
 * Series of bloom filters which decay over time, allowing their continual use
 * for time sensitive data.  This has a fixed size (currently 1MB per decay 
 * period, using two periods overall), allowing this to pump through hundreds of
 * entries per second with virtually no false positive rate.  Down the line, 
 * this may be refactored to allow tighter control of the size necessary for the
 * contained bloom filters, but a fixed 2MB overhead isn't that bad.
 */
public class DecayingBloomFilter {
    private I2PAppContext _context;
    private Log _log;
    private BloomSHA1 _current;
    private BloomSHA1 _previous;
    private int _durationMs;
    private int _entryBytes;
    private byte _extenders[][];
    private byte _extended[];
    private byte _longToEntry[];
    private long _longToEntryMask;
    private long _currentDuplicates;
    private boolean _keepDecaying;
    private DecayEvent _decayEvent;
    
    private static final boolean ALWAYS_MISS = false;
   
    /**
     * Create a bloom filter that will decay its entries over time.  
     *
     * @param durationMs entries last for at least this long, but no more than twice this long
     * @param entryBytes how large are the entries to be added?  if this is less than 32 bytes,
     *                   the entries added will be expanded by concatenating their XORing 
     *                   against with sufficient random values.
     */
    public DecayingBloomFilter(I2PAppContext context, int durationMs, int entryBytes) {
        _context = context;
        _log = context.logManager().getLog(DecayingBloomFilter.class);
        _entryBytes = entryBytes;
        _current = new BloomSHA1(23, 11); //new BloomSHA1(23, 11);
        _previous = new BloomSHA1(23, 11); //new BloomSHA1(23, 11);
        _durationMs = durationMs;
        int numExtenders = (32+ (entryBytes-1))/entryBytes - 1;
        if (numExtenders < 0)
            numExtenders = 0;
        _extenders = new byte[numExtenders][entryBytes];
        for (int i = 0; i < numExtenders; i++)
            _context.random().nextBytes(_extenders[i]);
        if (numExtenders > 0) {
            _extended = new byte[32];
            _longToEntry = new byte[_entryBytes];
            _longToEntryMask = (1l << (_entryBytes * 8l)) -1;
        }
        _currentDuplicates = 0;
        _decayEvent = new DecayEvent();
        _keepDecaying = true;
        SimpleTimer.getInstance().addEvent(_decayEvent, _durationMs);
    }
    
    public long getCurrentDuplicateCount() { return _currentDuplicates; }
    public int getInsertedCount() { 
        synchronized (this) {
            return _current.size() + _previous.size(); 
        }
    }
    public double getFalsePositiveRate() { 
        synchronized (this) {
            return _current.falsePositives(); 
        }
    }
    
    /** 
     * return true if the entry added is a duplicate
     *
     */
    public boolean add(byte entry[]) {
        return add(entry, 0, entry.length);
    }
    public boolean add(byte entry[], int off, int len) {
        if (ALWAYS_MISS) return false;
        if (entry == null) 
            throw new IllegalArgumentException("Null entry");
        if (len != _entryBytes) 
            throw new IllegalArgumentException("Bad entry [" + len + ", expected " 
                                               + _entryBytes + "]");
        synchronized (this) {
            return locked_add(entry, off, len);
        }
    }
    
    /** 
     * return true if the entry added is a duplicate.  the number of low order 
     * bits used is determined by the entryBytes parameter used on creation of the
     * filter.
     *
     */
    public boolean add(long entry) {
        if (ALWAYS_MISS) return false;
        synchronized (this) {
            if (_entryBytes <= 7)
                entry = ((entry ^ _longToEntryMask) & ((1 << 31)-1)) | (entry ^ _longToEntryMask);
                //entry &= _longToEntryMask; 
            if (entry < 0) {
                DataHelper.toLong(_longToEntry, 0, _entryBytes, 0-entry);
                _longToEntry[0] |= (1 << 7);
            } else {
                DataHelper.toLong(_longToEntry, 0, _entryBytes, entry);
            }
            return locked_add(_longToEntry, 0, _longToEntry.length);
        }
    }
    
    /** 
     * return true if the entry is already known.  this does NOT add the
     * entry however.
     *
     */
    public boolean isKnown(long entry) {
        if (ALWAYS_MISS) return false;
        synchronized (this) {
            if (_entryBytes <= 7)
                entry = ((entry ^ _longToEntryMask) & ((1 << 31)-1)) | (entry ^ _longToEntryMask); 
            if (entry < 0) {
                DataHelper.toLong(_longToEntry, 0, _entryBytes, 0-entry);
                _longToEntry[0] |= (1 << 7);
            } else {
                DataHelper.toLong(_longToEntry, 0, _entryBytes, entry);
            }
            return locked_add(_longToEntry, 0, _longToEntry.length, false);
        }
    }
    
    private boolean locked_add(byte entry[], int offset, int len) {
        return locked_add(entry, offset, len, true);
    }
    private boolean locked_add(byte entry[], int offset, int len, boolean addIfNew) {
        if (_extended != null) {
            // extend the entry to 32 bytes
            System.arraycopy(entry, offset, _extended, 0, len);
            for (int i = 0; i < _extenders.length; i++)
                DataHelper.xor(entry, offset, _extenders[i], 0, _extended, _entryBytes * (i+1), _entryBytes);

            boolean seen = _current.locked_member(_extended);
            seen = seen || _previous.locked_member(_extended);
            if (seen) {
                _currentDuplicates++;
                return true;
            } else {
                if (addIfNew) {
                    _current.locked_insert(_extended);
                    _previous.locked_insert(_extended);
                }
                return false;
            }
        } else {
            boolean seen = _current.locked_member(entry, offset, len);
            seen = seen || _previous.locked_member(entry, offset, len);
            if (seen) {
                _currentDuplicates++;
                return true;
            } else {
                if (addIfNew) {
                    _current.locked_insert(entry, offset, len);
                    _previous.locked_insert(entry, offset, len);
                }
                return false;
            }
        }
    }
    
    public void clear() {
        synchronized (this) {
            _current.clear();
            _previous.clear();
            _currentDuplicates = 0;
        }
    }
    
    public void stopDecaying() {
        _keepDecaying = false;
        SimpleTimer.getInstance().removeEvent(_decayEvent);
    }
    
    private void decay() {
        int currentCount = 0;
        long dups = 0;
        synchronized (this) {
            BloomSHA1 tmp = _previous;
            currentCount = _current.size();
            _previous = _current;
            _current = tmp;
            _current.clear();
            dups = _currentDuplicates;
            _currentDuplicates = 0;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Decaying the filter after inserting " + currentCount 
                       + " elements and " + dups + " false positives");
    }
    
    private class DecayEvent implements SimpleTimer.TimedEvent {
        public void timeReached() {
            if (_keepDecaying) {
                decay();
                SimpleTimer.getInstance().addEvent(DecayEvent.this, _durationMs);
            }
        }
    }
    
    public static void main(String args[]) {
        int kbps = 256;
        int iterations = 100;
        testByLong(kbps, iterations);
        testByBytes(kbps, iterations);
    }
    public static void testByLong(int kbps, int numRuns) {
        int messages = 60 * 10 * kbps;
        Random r = new Random();
        DecayingBloomFilter filter = new DecayingBloomFilter(I2PAppContext.getGlobalContext(), 600*1000, 8);
        int falsePositives = 0;
        long totalTime = 0;
        for (int j = 0; j < numRuns; j++) {
            long start = System.currentTimeMillis();
            for (int i = 0; i < messages; i++) {
                if (filter.add(r.nextLong())) {
                    falsePositives++;
                    System.out.println("False positive " + falsePositives + " (testByLong j=" + j + " i=" + i + ")");
                }
            }
            totalTime += System.currentTimeMillis() - start;
            filter.clear();
        }
        filter.stopDecaying();
        System.out.println("After " + numRuns + " runs pushing " + messages + " entries in "
                           + DataHelper.formatDuration(totalTime/numRuns) + " per run, there were "
                           + falsePositives + " false positives");

    }
    public static void testByBytes(int kbps, int numRuns) {
        byte iv[][] = new byte[60*10*kbps][16];
        Random r = new Random();
        for (int i = 0; i < iv.length; i++)
            r.nextBytes(iv[i]);

        DecayingBloomFilter filter = new DecayingBloomFilter(I2PAppContext.getGlobalContext(), 600*1000, 16);
        int falsePositives = 0;
        long totalTime = 0;
        for (int j = 0; j < numRuns; j++) {
            long start = System.currentTimeMillis();
            for (int i = 0; i < iv.length; i++) {
                if (filter.add(iv[i])) {
                    falsePositives++;
                    System.out.println("False positive " + falsePositives + " (testByLong j=" + j + " i=" + i + ")");
                }
            }
            totalTime += System.currentTimeMillis() - start;
            filter.clear();
        }
        filter.stopDecaying();
        System.out.println("After " + numRuns + " runs pushing " + iv.length + " entries in "
                           + DataHelper.formatDuration(totalTime/numRuns) + " per run, there were "
                           + falsePositives + " false positives");
        //System.out.println("inserted: " + bloom.size() + " with " + bloom.capacity() 
        //                   + " (" + bloom.falsePositives()*100.0d + "% false positive)");
    }
}
