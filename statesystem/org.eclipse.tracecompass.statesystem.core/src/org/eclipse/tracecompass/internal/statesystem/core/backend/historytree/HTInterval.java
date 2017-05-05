/*******************************************************************************
 * Copyright (c) 2012, 2015 Ericsson, École Polytechnique de Montréal
 * Copyright (c) 2010, 2011 Alexandre Montplaisir <alexandre.montplaisir@gmail.com>
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Alexandre Montplaisir - Initial API and implementation
 *    Florian Wininger - Allow to change the size of a interval
 *    Patrick Tasse - Add message to exceptions
 *******************************************************************************/

package org.eclipse.tracecompass.internal.statesystem.core.backend.historytree;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

import org.eclipse.tracecompass.datastore.core.serialization.ISafeByteBufferReader;
import org.eclipse.tracecompass.datastore.core.serialization.ISafeByteBufferWriter;
import org.eclipse.tracecompass.datastore.core.serialization.SafeByteBufferFactory;
import org.eclipse.tracecompass.internal.provisional.statesystem.core.statevalue.CustomStateValue;
import org.eclipse.tracecompass.statesystem.core.exceptions.TimeRangeException;
import org.eclipse.tracecompass.statesystem.core.interval.ITmfStateInterval;
import org.eclipse.tracecompass.statesystem.core.statevalue.ITmfStateValue;
import org.eclipse.tracecompass.statesystem.core.statevalue.TmfStateValue;

/**
 * The interval component, which will be contained in a node of the History
 * Tree.
 *
 * @author Alexandre Montplaisir
 */
public final class HTInterval implements ITmfStateInterval {

    private static final Charset CHARSET = Charset.forName("UTF-8"); //$NON-NLS-1$

    private static final String errMsg = "Invalid interval data. Maybe your file is corrupt?"; //$NON-NLS-1$

    /* 'Byte' equivalent for state values types */
    private static final byte TYPE_NULL = -1;
    private static final byte TYPE_INTEGER = 0;
    private static final byte TYPE_STRING = 1;
    private static final byte TYPE_LONG = 2;
    private static final byte TYPE_DOUBLE = 3;
    private static final byte TYPE_CUSTOM = 20;

    private final long start;
    private final long end;
    private final int attribute;
    private final Object sv;

    /** Number of bytes used by this interval when it is written to disk */
    private final int fSizeOnDisk;

    /**
     * Standard constructor
     *
     * @param intervalStart
     *            Start time of the interval
     * @param intervalEnd
     *            End time of the interval
     * @param attribute
     *            Attribute (quark) to which the state represented by this
     *            interval belongs
     * @param value
     *            State value represented by this interval
     * @throws TimeRangeException
     *             If the start time or end time are invalid
     */
    public HTInterval(long intervalStart, long intervalEnd, int attribute,
            Object value) throws TimeRangeException {
        if (intervalStart > intervalEnd) {
            throw new TimeRangeException("Start:" + intervalStart + ", End:" + intervalEnd); //$NON-NLS-1$ //$NON-NLS-2$
        }

        this.start = intervalStart;
        this.end = intervalEnd;
        this.attribute = attribute;
        this.sv = (value instanceof TmfStateValue) ? ((ITmfStateValue) value).unboxValue() : value;
        this.fSizeOnDisk = computeSizeOnDisk(sv);
    }

    /**
     * Compute how much space (in bytes) an interval will take in its serialized
     * form on disk. This is dependent on its state value.
     */
    private static int computeSizeOnDisk(Object sv) {
        /*
         * Minimum size is 2x long (start and end), 1x int (attribute) and 1x
         * byte (value type).
         */
        int minSize = Long.BYTES + Long.BYTES + Integer.BYTES + Byte.BYTES;

        if (sv == null) {
            return minSize;
        } else if (sv instanceof Integer) {
            return (minSize + Integer.BYTES);
        } else if (sv instanceof Long) {
            return (minSize + Long.BYTES);
        } else if (sv instanceof Double) {
            return (minSize + Double.BYTES);
        } else if (sv instanceof String) {
            String str = (String) sv;
            int strLength = str.getBytes(CHARSET).length;

            if (strLength > Short.MAX_VALUE) {
                throw new IllegalArgumentException("String is too long to be stored in state system: " + str); //$NON-NLS-1$
            }

            /*
             * String's length + 3 (2 bytes for size, 1 byte for \0 at the end)
             */
            return (minSize + strLength + 3);
        } else if (sv instanceof CustomStateValue) {
            /* Length of serialized value (short) + state value */
            return (minSize + Short.BYTES + ((CustomStateValue) sv).getSerializedSize());
        }
        /*
         * It's very important that we know how to write the state value in the
         * file!!
         */
        throw new IllegalStateException();
    }

    /**
     * "Faster" constructor for inner use only. When we build an interval when
     * reading it from disk (with {@link #readFrom}), we already know the size
     * of the strings entry, so there is no need to call
     * {@link #computeStringsEntrySize()} and do an extra copy.
     */
    private HTInterval(long intervalStart, long intervalEnd, int attribute,
            Object value, int size) throws TimeRangeException {
        if (intervalStart > intervalEnd) {
            throw new TimeRangeException("Start:" + intervalStart + ", End:" + intervalEnd); //$NON-NLS-1$ //$NON-NLS-2$
        }

        this.start = intervalStart;
        this.end = intervalEnd;
        this.attribute = attribute;
        this.sv = value;
        this.fSizeOnDisk = size;
    }

    /**
     * Reader factory method. Builds the interval using an already-allocated
     * ByteBuffer, which normally comes from a NIO FileChannel.
     *
     * The interval is just a start, end, attribute and value, this is the
     * layout of the HTInterval on disk
     * <ul>
     * <li>start (8 bytes)</li>
     * <li>end (8 bytes)</li>
     * <li>attribute (4 bytes)</li>
     * <li>sv type (1 byte)</li>
     * <li>sv ( 0 bytes for null, 4 for int , 8 for long and double, and the
     * length of the string +2 for strings (it's variable))</li>
     * </ul>
     *
     * @param buffer
     *            The ByteBuffer from which to read the information
     * @return The interval object
     * @throws IOException
     *             If there was an error reading from the buffer
     */
    public static final HTInterval readFrom(ByteBuffer buffer) throws IOException {
        Object value;

        int posStart = buffer.position();
        /* Read the Data Section entry */
        long intervalStart = buffer.getLong();
        long intervalEnd = buffer.getLong();
        int attribute = buffer.getInt();

        /* Read the 'type' of the value, then react accordingly */
        byte valueType = buffer.get();
        switch (valueType) {

        case TYPE_NULL:
            value = null;
            break;

        case TYPE_INTEGER:
            value = buffer.getInt();
            break;

        case TYPE_STRING: {
            /* the first short = the size to read */
            int valueSize = buffer.getShort();

            byte[] array = new byte[valueSize];
            buffer.get(array);
            value = new String(array, CHARSET);

            /* Confirm the 0'ed byte at the end */
            byte res = buffer.get();
            if (res != 0) {
                throw new IOException(errMsg);
            }
            break;
        }

        case TYPE_LONG:
            /* Go read the matching entry in the Strings section of the block */
            value = buffer.getLong();
            break;

        case TYPE_DOUBLE:
            /* Go read the matching entry in the Strings section of the block */
            value = buffer.getDouble();
            break;

        case TYPE_CUSTOM: {
            short valueSize = buffer.getShort();
            ISafeByteBufferReader safeBuffer = SafeByteBufferFactory.wrapReader(buffer, valueSize);
            value = CustomStateValue.readSerializedValue(safeBuffer);
            break;
        }
        default:
            /* Unknown data, better to not make anything up... */
            throw new IOException(errMsg);
        }

        try {
            return new HTInterval(intervalStart, intervalEnd, attribute, value, buffer.position() - posStart);
        } catch (TimeRangeException e) {
            throw new IOException(errMsg);
        }
    }

    /**
     * Antagonist of the previous constructor, write the Data entry
     * corresponding to this interval in a ByteBuffer (mapped to a block in the
     * history-file, hopefully)
     *
     * The interval is just a start, end, attribute and value, this is the
     * layout of the HTInterval on disk
     * <ul>
     * <li>start (8 bytes)</li>
     * <li>end (8 bytes)</li>
     * <li>attribute (4 bytes)</li>
     * <li>sv type (1 byte)</li>
     * <li>sv ( 0 bytes for null, 4 for int , 8 for long and double, and the
     * length of the string +2 for strings (it's variable))</li>
     * </ul>
     *
     * @param buffer
     *            The already-allocated ByteBuffer corresponding to a SHT Node
     */
    public void writeInterval(ByteBuffer buffer) {
        final byte byteFromType = getByteFromType(sv);

        buffer.putLong(start);
        buffer.putLong(end);
        buffer.putInt(attribute);
        buffer.put(byteFromType);

        switch (byteFromType) {
        case TYPE_NULL:
            break;
        case TYPE_INTEGER:
            buffer.putInt((int) sv);
            break;

        case TYPE_STRING: {
            String string = (String) sv;
            byte[] strArray = string.getBytes(CHARSET);

            /*
             * Write the Strings entry (1st byte = size, then the bytes, then
             * the 0). We have checked the string length at the constructor.
             */
            buffer.putShort((short) strArray.length);
            buffer.put(strArray);
            buffer.put((byte) 0);
            break;
        }

        case TYPE_LONG:
            buffer.putLong((long) sv);
            break;

        case TYPE_DOUBLE:
            buffer.putDouble((double) sv);
            break;

        case TYPE_CUSTOM: {
            int size = ((CustomStateValue) sv).getSerializedSize();
            buffer.putShort((short) size);
            ISafeByteBufferWriter safeBuffer = SafeByteBufferFactory.wrapWriter(buffer, size);
            ((CustomStateValue) sv).serialize(safeBuffer);
            break;
        }

        default:
            break;
        }
    }

    @Override
    public long getStartTime() {
        return start;
    }

    @Override
    public long getEndTime() {
        return end;
    }

    @Override
    public int getAttribute() {
        return attribute;
    }

    @Override
    public ITmfStateValue getStateValue() {
        return TmfStateValue.newValue(sv);
    }

    @Override
    public Object getValue() {
        return sv;
    }

    @Override
    public boolean intersects(long timestamp) {
        if (start <= timestamp) {
            if (end >= timestamp) {
                return true;
            }
        }
        return false;
    }

    /**
     * Total serialized size of this interval
     *
     * @return The interval size
     */
    public int getSizeOnDisk() {
        return fSizeOnDisk;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        HTInterval other = (HTInterval) obj;
        return (start == other.start &&
                end == other.end &&
                attribute == other.attribute &&
                sv.equals(other.sv));
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, attribute, sv);
    }

    @Override
    public String toString() {
        /* Only for debug, should not be externalized */
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        sb.append(start);
        sb.append(", "); //$NON-NLS-1$
        sb.append(end);
        sb.append(']');

        sb.append(", attribute = "); //$NON-NLS-1$
        sb.append(attribute);

        sb.append(", value = "); //$NON-NLS-1$
        sb.append(sv.toString());

        return sb.toString();
    }

    /**
     * Here we determine how state values "types" are written in the 8-bit field
     * that indicates the value type in the file.
     */
    private static byte getByteFromType(Object type) {
        if (type == null) {
            return TYPE_NULL;
        } else if (type instanceof Integer) {
            return TYPE_INTEGER;
        } else if (type instanceof String) {
            return TYPE_STRING;
        } else if (type instanceof Long) {
            return TYPE_LONG;
        } else if (type instanceof Double) {
            return TYPE_DOUBLE;
        } else if (type instanceof CustomStateValue) {
            return TYPE_CUSTOM;
        }
        /* Should not happen if the switch is fully covered */
        throw new IllegalStateException();
    }
}
