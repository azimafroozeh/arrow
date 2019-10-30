/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.arrow.vector.complex;

import static java.util.Collections.singletonList;
import static org.apache.arrow.util.Preconditions.checkNotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.memory.util.ByteFunctionHelpers;
import org.apache.arrow.memory.util.hash.ArrowBufHasher;
import org.apache.arrow.util.Preconditions;
import org.apache.arrow.vector.AddOrGetResult;
import org.apache.arrow.vector.BitVectorHelper;
import org.apache.arrow.vector.BufferBacked;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.ZeroVector;
import org.apache.arrow.vector.compare.VectorVisitor;
import org.apache.arrow.vector.complex.impl.ComplexCopier;
import org.apache.arrow.vector.complex.impl.UnionListReader;
import org.apache.arrow.vector.complex.impl.UnionListWriter;
import org.apache.arrow.vector.complex.reader.FieldReader;
import org.apache.arrow.vector.complex.writer.FieldWriter;
import org.apache.arrow.vector.ipc.message.ArrowFieldNode;
import org.apache.arrow.vector.types.Types.MinorType;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.DictionaryEncoding;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.util.CallBack;
import org.apache.arrow.vector.util.JsonStringArrayList;
import org.apache.arrow.vector.util.TransferPair;

import io.netty.buffer.ArrowBuf;

/**
 * A list vector contains lists of a specific type of elements.  Its structure contains 3 elements.
 * <ol>
 * <li>A validity buffer.</li>
 * <li> An offset buffer, that denotes lists boundaries. </li>
 * <li> A child data vector that contains the elements of lists. </li>
 * </ol>
 * The latter two are managed by its superclass.
 */
public class ListVector extends BaseRepeatedValueVector implements PromotableVector {

  public static ListVector empty(String name, BufferAllocator allocator) {
    return new ListVector(name, allocator, FieldType.nullable(ArrowType.List.INSTANCE), null);
  }

  protected UnionListReader reader;
  private CallBack callBack;
  private final FieldType fieldType;

  /**
   * The maximum index that is actually set.
   */
  private int lastSet;

  /**
   * @deprecated Use FieldType or static constructor instead.
   */
  @Deprecated
  public ListVector(String name, BufferAllocator allocator, CallBack callBack) {
    this(name, allocator, FieldType.nullable(ArrowType.List.INSTANCE), callBack);
  }

  /**
   * @deprecated Use FieldType or static constructor instead.
   */
  @Deprecated
  public ListVector(String name, BufferAllocator allocator, DictionaryEncoding dictionary, CallBack callBack) {
    this(name, allocator, new FieldType(true, ArrowType.List.INSTANCE, dictionary, null), callBack);
  }

  /**
   * Constructs a new instance.
   *
   * @param name The name of the instance.
   * @param allocator The allocator to use for allocating/reallocating buffers.
   * @param fieldType The type of this list.
   * @param callBack A schema change callback.
   */
  public ListVector(String name, BufferAllocator allocator, FieldType fieldType, CallBack callBack) {
    super(name, allocator, callBack);
    this.fieldType = checkNotNull(fieldType);
    this.callBack = callBack;
    this.lastSet = -1;
  }

  @Override
  public void initializeChildrenFromFields(List<Field> children) {
    if (children.size() != 1) {
      throw new IllegalArgumentException("Lists have only one child. Found: " + children);
    }
    Field field = children.get(0);
    AddOrGetResult<FieldVector> addOrGetVector = addOrGetVector(field.getFieldType());
    if (!addOrGetVector.isCreated()) {
      throw new IllegalArgumentException("Child vector already existed: " + addOrGetVector.getVector());
    }

    addOrGetVector.getVector().initializeChildrenFromFields(field.getChildren());
  }

  @Override
  public void setInitialCapacity(int numRecords) {
    setValidityAllocationSizeInBytes(getValidityBufferSizeFromCount(numRecords));
    super.setInitialCapacity(numRecords);
  }

  /**
   * Specialized version of setInitialCapacity() for ListVector. This is
   * used by some callers when they want to explicitly control and be
   * conservative about memory allocated for inner data vector. This is
   * very useful when we are working with memory constraints for a query
   * and have a fixed amount of memory reserved for the record batch. In
   * such cases, we are likely to face OOM or related problems when
   * we reserve memory for a record batch with value count x and
   * do setInitialCapacity(x) such that each vector allocates only
   * what is necessary and not the default amount but the multiplier
   * forces the memory requirement to go beyond what was needed.
   *
   * @param numRecords value count
   * @param density density of ListVector. Density is the average size of
   *                list per position in the List vector. For example, a
   *                density value of 10 implies each position in the list
   *                vector has a list of 10 values.
   *                A density value of 0.1 implies out of 10 positions in
   *                the list vector, 1 position has a list of size 1 and
   *                remaining positions are null (no lists) or empty lists.
   *                This helps in tightly controlling the memory we provision
   *                for inner data vector.
   */
  @Override
  public void setInitialCapacity(int numRecords, double density) {
    validityAllocationSizeInBytes = getValidityBufferSizeFromCount(numRecords);
    super.setInitialCapacity(numRecords, density);
  }

  /**
   * Get the density of this ListVector.
   * @return density
   */
  public double getDensity() {
    if (valueCount == 0) {
      return 0.0D;
    }
    final int startOffset = offsetBuffer.getInt(0);
    final int endOffset = offsetBuffer.getInt(valueCount * OFFSET_WIDTH);
    final double totalListSize = endOffset - startOffset;
    return totalListSize / valueCount;
  }

  @Override
  public List<FieldVector> getChildrenFromFields() {
    return singletonList(getDataVector());
  }

  /**
   * Load the buffers of this vector with provided source buffers.
   * The caller manages the source buffers and populates them before invoking
   * this method.
   * @param fieldNode  the fieldNode indicating the value count
   * @param ownBuffers the buffers for this Field (own buffers only, children not included)
   */
  @Override
  public void loadFieldBuffers(ArrowFieldNode fieldNode, List<ArrowBuf> ownBuffers) {
    if (ownBuffers.size() != 2) {
      throw new IllegalArgumentException("Illegal buffer count, expected " + 2 + ", got: " + ownBuffers.size());
    }

    ArrowBuf bitBuffer = ownBuffers.get(0);
    ArrowBuf offBuffer = ownBuffers.get(1);


    loadValidityBuffer(fieldNode, bitBuffer);
    offsetBuffer.getReferenceManager().release();
    offsetBuffer = offBuffer.getReferenceManager().retain(offBuffer, allocator);

    validityAllocationSizeInBytes = validityBuffer.capacity();
    offsetAllocationSizeInBytes = offsetBuffer.capacity();

    lastSet = fieldNode.getLength() - 1;
    valueCount = fieldNode.getLength();
  }

  /**
   * Get the buffers belonging to this vector.
   * @return the inner buffers.
   */
  @Override
  public List<ArrowBuf> getFieldBuffers() {
    List<ArrowBuf> result = new ArrayList<>(2);
    setReaderAndWriterIndex();
    add(result);
    result.add(offsetBuffer);

    return result;
  }

  /**
   * Set the reader and writer indexes for the inner buffers.
   */
  private void setReaderAndWriterIndex() {
    setValidityBufferReaderIndex(0);
    offsetBuffer.readerIndex(0);
    if (valueCount == 0) {
      setValidityBufferWriterIndex(0);
      offsetBuffer.writerIndex(0);
    } else {
      setValidityBufferWriterIndex(getValidityBufferSizeFromCount(valueCount));
      offsetBuffer.writerIndex((valueCount + 1) * OFFSET_WIDTH);
    }
  }

  @Override
  @Deprecated
  public List<BufferBacked> getFieldInnerVectors() {
    throw new UnsupportedOperationException("There are no inner vectors. Use getFieldBuffers");
  }

  public UnionListWriter getWriter() {
    return new UnionListWriter(this);
  }

  /**
   * Same as {@link #allocateNewSafe()}.
   */
  @Override
  public void allocateNew() throws OutOfMemoryException {
    if (!allocateNewSafe()) {
      throw new OutOfMemoryException("Failure while allocating memory");
    }
  }

  /**
   * Allocate memory for the vector. We internally use a default value count
   * of 4096 to allocate memory for at least these many elements in the
   * vector.
   *
   * @return false if memory allocation fails, true otherwise.
   */
  public boolean allocateNewSafe() {
    boolean success = false;
    try {
      /* we are doing a new allocation -- release the current buffers */
      clear();
      /* allocate validity buffer */
      allocateValidityBuffer(validityAllocationSizeInBytes);
      /* allocate offset and data buffer */
      success = super.allocateNewSafe();
    } finally {
      if (!success) {
        clear();
        return false;
      }
    }
    return true;
  }


  /**
   * Resize the vector to increase the capacity. The internal behavior is to
   * double the current value capacity.
   */
  @Override
  public void reAlloc() {
    /* reallocate the validity buffer */
    reallocValidityBuffer();
    /* reallocate the offset and data */
    super.reAlloc();
  }

  private void reallocValidityAndOffsetBuffers() {
    reallocOffsetBuffer();
    reallocValidityBuffer();
  }

  /**
   * Same as {@link #copyFrom(int, int, ValueVector)} except that
   * it handles the case when the capacity of the vector needs to be expanded
   * before copy.
   * @param inIndex position to copy from in source vector
   * @param outIndex position to copy to in this vector
   * @param from source vector
   */
  @Override
  public void copyFromSafe(int inIndex, int outIndex, ValueVector from) {
    copyFrom(inIndex, outIndex, from);
  }

  /**
   * Copy a cell value from a particular index in source vector to a particular
   * position in this vector.
   * @param inIndex position to copy from in source vector
   * @param outIndex position to copy to in this vector
   * @param from source vector
   */
  @Override
  public void copyFrom(int inIndex, int outIndex, ValueVector from) {
    Preconditions.checkArgument(this.getMinorType() == from.getMinorType());
    FieldReader in = from.getReader();
    in.setPosition(inIndex);
    FieldWriter out = getWriter();
    out.setPosition(outIndex);
    ComplexCopier.copy(in, out);
  }

  /**
   * Get the inner data vector for this list vector.
   * @return data vector
   */
  @Override
  public FieldVector getDataVector() {
    return vector;
  }

  @Override
  public TransferPair getTransferPair(String ref, BufferAllocator allocator) {
    return getTransferPair(ref, allocator, null);
  }

  @Override
  public TransferPair getTransferPair(String ref, BufferAllocator allocator, CallBack callBack) {
    return new TransferImpl(ref, allocator, callBack);
  }

  @Override
  public TransferPair makeTransferPair(ValueVector target) {
    return new TransferImpl((ListVector) target);
  }

  @Override
  public long getDataBufferAddress() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getOffsetBufferAddress() {
    return (offsetBuffer.memoryAddress());
  }

  @Override
  public ArrowBuf getDataBuffer() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ArrowBuf getOffsetBuffer() {
    return offsetBuffer;
  }

  @Override
  public int hashCode(int index) {
    return hashCode(index, null);
  }

  @Override
  public int hashCode(int index, ArrowBufHasher hasher) {
    if (isSet(index) == 0) {
      return 0;
    }
    int hash = 0;
    final int start = offsetBuffer.getInt(index * OFFSET_WIDTH);
    final int end = offsetBuffer.getInt((index + 1) * OFFSET_WIDTH);
    for (int i = start; i < end; i++) {
      hash = ByteFunctionHelpers.combineHash(hash, vector.hashCode(i, hasher));
    }
    return hash;
  }

  @Override
  public <OUT, IN> OUT accept(VectorVisitor<OUT, IN> visitor, IN value) {
    return visitor.visit(this, value);
  }

  private class TransferImpl implements TransferPair {

    ListVector to;
    TransferPair dataTransferPair;

    public TransferImpl(String name, BufferAllocator allocator, CallBack callBack) {
      this(new ListVector(name, allocator, fieldType, callBack));
    }

    public TransferImpl(ListVector to) {
      this.to = to;
      to.addOrGetVector(vector.getField().getFieldType());
      if (to.getDataVector() instanceof ZeroVector) {
        to.addOrGetVector(vector.getField().getFieldType());
      }
      dataTransferPair = getDataVector().makeTransferPair(to.getDataVector());
    }

    /**
     * Transfer this vector'data to another vector. The memory associated
     * with this vector is transferred to the allocator of target vector
     * for accounting and management purposes.
     */
    @Override
    public void transfer() {
      to.clear();
      dataTransferPair.transfer();
      transferValidityBuffer(to);
      to.offsetBuffer = transferBuffer(offsetBuffer, to.allocator);
      to.lastSet = lastSet;
      if (valueCount > 0) {
        to.setValueCount(valueCount);
      }
      clear();
    }

    /**
     * Slice this vector at desired index and length and transfer the
     * corresponding data to the target vector.
     * @param startIndex start position of the split in source vector.
     * @param length length of the split.
     */
    @Override
    public void splitAndTransfer(int startIndex, int length) {
      Preconditions.checkArgument(startIndex + length <= valueCount);
      final int startPoint = offsetBuffer.getInt(startIndex * OFFSET_WIDTH);
      final int sliceLength = offsetBuffer.getInt((startIndex + length) * OFFSET_WIDTH) - startPoint;
      to.clear();
      to.allocateOffsetBuffer((length + 1) * OFFSET_WIDTH);
      /* splitAndTransfer offset buffer */
      for (int i = 0; i < length + 1; i++) {
        final int relativeOffset = offsetBuffer.getInt((startIndex + i) * OFFSET_WIDTH) - startPoint;
        to.offsetBuffer.setInt(i * OFFSET_WIDTH, relativeOffset);
      }
      /* splitAndTransfer validity buffer */
      splitAndTransferValidityBuffer(startIndex, length, valueCount, to);
      /* splitAndTransfer data buffer */
      dataTransferPair.splitAndTransfer(startPoint, sliceLength);
      to.lastSet = length - 1;
      to.setValueCount(length);
    }

    @Override
    public ValueVector getTo() {
      return to;
    }

    @Override
    public void copyValueSafe(int from, int to) {
      this.to.copyFrom(from, to, ListVector.this);
    }
  }

  @Override
  public UnionListReader getReader() {
    if (reader == null) {
      reader = new UnionListReader(this);
    }
    return reader;
  }

  /** Initialize the child data vector to field type.  */
  public <T extends ValueVector> AddOrGetResult<T> addOrGetVector(FieldType fieldType) {
    AddOrGetResult<T> result = super.addOrGetVector(fieldType);
    invalidateReader();
    return result;
  }

  /**
   * Get the size (number of bytes) of underlying buffers used by this
   * vector.
   * @return size of underlying buffers.
   */
  @Override
  public int getBufferSize() {
    if (valueCount == 0) {
      return 0;
    }
    final int offsetBufferSize = (valueCount + 1) * OFFSET_WIDTH;
    final int validityBufferSize = getValidityBufferSizeFromCount(valueCount);
    return offsetBufferSize + validityBufferSize + vector.getBufferSize();
  }

  @Override
  public int getBufferSizeFor(int valueCount) {
    if (valueCount == 0) {
      return 0;
    }
    final int validityBufferSize = getValidityBufferSizeFromCount(valueCount);

    return super.getBufferSizeFor(valueCount) + validityBufferSize;
  }

  @Override
  public Field getField() {
    return new Field(getName(), fieldType, Collections.singletonList(getDataVector().getField()));
  }

  @Override
  public MinorType getMinorType() {
    return MinorType.LIST;
  }

  @Override
  public void clear() {
    super.clear();
    releaseValidityBuffer();
    lastSet = -1;
  }

  @Override
  public void reset() {
    super.reset();
    initValidityBuffer();
    lastSet = -1;
  }

  /**
   * Return the underlying buffers associated with this vector. Note that this doesn't
   * impact the reference counts for this buffer so it only should be used for in-context
   * access. Also note that this buffer changes regularly thus
   * external classes shouldn't hold a reference to it (unless they change it).
   *
   * @param clear Whether to clear vector before returning; the buffers will still be refcounted
   *              but the returned array will be the only reference to them
   * @return The underlying {@link io.netty.buffer.ArrowBuf buffers} that is used by this
   *         vector instance.
   */
  @Override
  public ArrowBuf[] getBuffers(boolean clear) {
    setReaderAndWriterIndex();
    final ArrowBuf[] buffers;
    if (getBufferSize() == 0) {
      buffers = new ArrowBuf[0];
    } else {
      List<ArrowBuf> list = new ArrayList<>();
      list.add(offsetBuffer);
      add(list);
      list.addAll(Arrays.asList(vector.getBuffers(false)));
      buffers = list.toArray(new ArrowBuf[list.size()]);
    }
    if (clear) {
      for (ArrowBuf buffer : buffers) {
        buffer.getReferenceManager().retain();
      }
      clear();
    }
    return buffers;
  }

  @Override
  public UnionVector promoteToUnion() {
    UnionVector vector = new UnionVector("$data$", allocator, callBack);
    replaceDataVector(vector);
    invalidateReader();
    if (callBack != null) {
      callBack.doWork();
    }
    return vector;
  }

  protected void invalidateReader() {
    reader = null;
  }

  /**
   * Get the element in the list vector at a particular index.
   * @param index position of the element
   * @return Object at given position
   */
  @Override
  public Object getObject(int index) {
    if (isSet(index) == 0) {
      return null;
    }
    final List<Object> vals = new JsonStringArrayList<>();
    final int start = offsetBuffer.getInt(index * OFFSET_WIDTH);
    final int end = offsetBuffer.getInt((index + 1) * OFFSET_WIDTH);
    final ValueVector vv = getDataVector();
    for (int i = start; i < end; i++) {
      vals.add(vv.getObject(i));
    }

    return vals;
  }

  /**
   * Check if element at given index is null.
   *
   * @param index  position of element
   * @return true if element at given index is null, false otherwise
   */
  @Override
  public boolean isNull(int index) {
    return (isSet(index) == 0);
  }

  /**
   * Same as {@link #isNull(int)}.
   *
   * @param index  position of element
   * @return 1 if element at given index is not null, 0 otherwise
   */
  public int isSet(int index) {
    final int byteIndex = index >> 3;
    final byte b = validityBuffer.getByte(byteIndex);
    final int bitIndex = index & 7;
    return (b >> bitIndex) & 0x01;
  }

  /**
   * Get the current value capacity for the vector.
   * @return number of elements that vector can hold.
   */
  @Override
  public int getValueCapacity() {
    return getValidityAndOffsetValueCapacity();
  }

  private int getValidityAndOffsetValueCapacity() {
    final int offsetValueCapacity = Math.max(getOffsetBufferValueCapacity() - 1, 0);
    return Math.min(offsetValueCapacity, getValidityBufferValueCapacity());
  }

  private int getValidityBufferValueCapacity() {
    return validityBuffer.capacity() * 8;
  }

  /**
   * Sets the list at index to be not-null.  Reallocates validity buffer if index
   * is larger than current capacity.
   */
  public void setNotNull(int index) {
    while (index >= getValidityAndOffsetValueCapacity()) {
      reallocValidityAndOffsetBuffers();
    }
    BitVectorHelper.setValidityBitToOne(validityBuffer, index);
    lastSet = index;
  }

  /**
   * Start a new value in the list vector.
   *
   * @param index index of the value to start
   */
  @Override
  public int startNewValue(int index) {
    while (index >= getValidityAndOffsetValueCapacity()) {
      reallocValidityAndOffsetBuffers();
    }
    for (int i = lastSet + 1; i <= index; i++) {
      final int currentOffset = offsetBuffer.getInt(i * OFFSET_WIDTH);
      offsetBuffer.setInt((i + 1) * OFFSET_WIDTH, currentOffset);
    }
    BitVectorHelper.setValidityBitToOne(validityBuffer, index);
    lastSet = index;
    return offsetBuffer.getInt((lastSet + 1) * OFFSET_WIDTH);
  }

  /**
   * End the current value.
   *
   * @param index index of the value to end
   * @param size  number of elements in the list that was written
   */
  public void endValue(int index, int size) {
    final int currentOffset = offsetBuffer.getInt((index + 1) * OFFSET_WIDTH);
    offsetBuffer.setInt((index + 1) * OFFSET_WIDTH, currentOffset + size);
  }

  /**
   * Sets the value count for the vector.
   *
   * @param valueCount   value count
   */
  @Override
  public void setValueCount(int valueCount) {
    this.valueCount = valueCount;
    if (valueCount > 0) {
      while (valueCount > getValidityAndOffsetValueCapacity()) {
        /* check if validity and offset buffers need to be re-allocated */
        reallocValidityAndOffsetBuffers();
      }
      for (int i = lastSet + 1; i < valueCount; i++) {
        /* fill the holes with offsets */
        final int currentOffset = offsetBuffer.getInt(i * OFFSET_WIDTH);
        offsetBuffer.setInt((i + 1) * OFFSET_WIDTH, currentOffset);
      }
    }
    /* valueCount for the data vector is the current end offset */
    final int childValueCount = (valueCount == 0) ? 0 :
            offsetBuffer.getInt((lastSet + 1) * OFFSET_WIDTH);
    /* set the value count of data vector and this will take care of
     * checking whether data buffer needs to be reallocated.
     */
    vector.setValueCount(childValueCount);
  }

  public void setLastSet(int value) {
    lastSet = value;
  }

  public int getLastSet() {
    return lastSet;
  }

  @Override
  public int getElementStartIndex(int index) {
    return offsetBuffer.getInt(index * OFFSET_WIDTH);
  }

  @Override
  public int getElementEndIndex(int index) {
    return offsetBuffer.getInt((index + 1) * OFFSET_WIDTH);
  }
}
