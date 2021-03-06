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

package org.apache.arrow.vector;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.impl.UnionListWriter;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestVectorSchemaRoot {
  private BufferAllocator allocator;

  @Before
  public void init() {
    allocator = new RootAllocator(Long.MAX_VALUE);
  }

  @After
  public void terminate() {
    allocator.close();
  }

  @Test
  public void testResetRowCount() {
    final int size = 20;
    try (final BitVector vec1 = new BitVector("bit", allocator);
         final IntVector vec2 = new IntVector("int", allocator)) {
      VectorSchemaRoot vsr = VectorSchemaRoot.of(vec1, vec2);

      vsr.allocateNew();
      assertEquals(vsr.getRowCount(), 0);

      for (int i = 0; i < size; i++) {
        vec1.setSafe(i, i % 2);
        vec2.setSafe(i, i);
      }
      vsr.setRowCount(size);
      checkCount(vec1, vec2, vsr, size);

      vsr.allocateNew();
      checkCount(vec1, vec2, vsr, 0);

      for (int i = 0; i < size; i++) {
        vec1.setSafe(i, i % 2);
        vec2.setSafe(i, i);
      }
      vsr.setRowCount(size);
      checkCount(vec1, vec2, vsr, size);

      vsr.clear();
      checkCount(vec1, vec2, vsr, 0);
    }
  }

  private void checkCount(BitVector vec1, IntVector vec2, VectorSchemaRoot vsr, int count) {
    assertEquals(vsr.getRowCount(), count);
  }

  private VectorSchemaRoot createBatch() {
    FieldType varCharType = new FieldType(true, new ArrowType.Utf8(), /*dictionary=*/null);
    FieldType listType = new FieldType(true, new ArrowType.List(), /*dictionary=*/null);

    // create the schema
    List<Field> schemaFields  = new ArrayList<>();
    Field childField = new Field("varCharCol", varCharType, null);
    List<Field> childFields = new ArrayList<>();
    childFields.add(childField);
    schemaFields.add(new Field("listCol", listType, childFields));
    Schema schema = new Schema(schemaFields);

    VectorSchemaRoot schemaRoot = VectorSchemaRoot.create(schema, allocator);
    // get and allocate the vector
    ListVector vector = (ListVector) schemaRoot.getVector("listCol");
    vector.allocateNew();

    // write data to the vector
    UnionListWriter writer = vector.getWriter();

    writer.setPosition(0);

    // write data vector(0)
    writer.startList();

    // write data vector(0)(0)
    writer.list().startList();

    // According to the schema above, the list element should have varchar type.
    // When we write a big int, the original writer cannot handle this, so the writer will
    // be promoted, and the vector structure will be different from the schema.
    writer.list().bigInt().writeBigInt(0);
    writer.list().bigInt().writeBigInt(1);
    writer.list().endList();

    // write data vector(0)(1)
    writer.list().startList();
    writer.list().float8().writeFloat8(3.0D);
    writer.list().float8().writeFloat8(7.0D);
    writer.list().endList();

    // finish data vector(0)
    writer.endList();

    writer.setPosition(1);

    // write data vector(1)
    writer.startList();

    // write data vector(1)(0)
    writer.list().startList();
    writer.list().integer().writeInt(3);
    writer.list().integer().writeInt(2);
    writer.list().endList();

    // finish data vector(1)
    writer.endList();

    vector.setValueCount(2);

    return schemaRoot;
  }

  @Test
  public void testAddVector() {
    try (final IntVector intVector1 = new IntVector("intVector1", allocator);
         final IntVector intVector2 = new IntVector("intVector2", allocator);
         final IntVector intVector3 = new IntVector("intVector3", allocator);) {

      VectorSchemaRoot original = new VectorSchemaRoot(Arrays.asList(intVector1, intVector2));
      assertEquals(2, original.getFieldVectors().size());

      VectorSchemaRoot newRecordBatch = original.addVector(1, intVector3);
      assertEquals(3, newRecordBatch.getFieldVectors().size());
      assertEquals(intVector3, newRecordBatch.getFieldVectors().get(1));

      original.close();
      newRecordBatch.close();
    }
  }

  @Test
  public void testRemoveVector() {
    try (final IntVector intVector1 = new IntVector("intVector1", allocator);
        final IntVector intVector2 = new IntVector("intVector2", allocator);
        final IntVector intVector3 = new IntVector("intVector3", allocator);) {

      VectorSchemaRoot original =
          new VectorSchemaRoot(Arrays.asList(intVector1, intVector2, intVector3));
      assertEquals(3, original.getFieldVectors().size());

      VectorSchemaRoot newRecordBatch = original.removeVector(0);
      assertEquals(2, newRecordBatch.getFieldVectors().size());
      assertEquals(intVector2, newRecordBatch.getFieldVectors().get(0));
      assertEquals(intVector3, newRecordBatch.getFieldVectors().get(1));

      original.close();
      newRecordBatch.close();
    }
  }

  @Test
  public void testSlice() {
    try (final IntVector intVector = new IntVector("intVector", allocator);
         final Float4Vector float4Vector = new Float4Vector("float4Vector", allocator)) {
      intVector.setValueCount(10);
      float4Vector.setValueCount(10);
      for (int i = 0; i < 10; i++) {
        intVector.setSafe(i, i);
        float4Vector.setSafe(i, i + 0.1f);
      }
      final VectorSchemaRoot original = new VectorSchemaRoot(Arrays.asList(intVector, float4Vector));

      VectorSchemaRoot slice1 = original.slice(0, original.getRowCount());
      assertEquals(original, slice1);

      VectorSchemaRoot slice2 = original.slice(0, 5);
      assertEquals(5, slice2.getRowCount());
      // validate data
      IntVector childVector1 = (IntVector) slice2.getFieldVectors().get(0);
      Float4Vector childVector2 = (Float4Vector) slice2.getFieldVectors().get(1);
      for (int i = 0; i < 5; i++) {
        assertEquals(i, childVector1.get(i));
        assertEquals(i + 0.1f, childVector2.get(i), 0);
      }

      original.close();
      slice2.close();
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testSliceWithInvalidParam() {
    try (final IntVector intVector = new IntVector("intVector", allocator);
         final Float4Vector float4Vector = new Float4Vector("float4Vector", allocator)) {
      intVector.allocateNew(10);
      float4Vector.allocateNew(10);
      for (int i = 0; i < 10; i++) {
        intVector.setSafe(i, i);
        float4Vector.setSafe(i, i + 0.1f);
      }
      final VectorSchemaRoot original = new VectorSchemaRoot(Arrays.asList(intVector, float4Vector));

      original.slice(0, 20);
    }
  }

  @Test
  public void testSchemaSync() {
    //create vector schema root
    try (VectorSchemaRoot schemaRoot = createBatch()) {
      Schema newSchema = new Schema(
              schemaRoot.getFieldVectors().stream().map(vec -> vec.getField()).collect(Collectors.toList()));

      assertNotEquals(newSchema, schemaRoot.getSchema());
      assertTrue(schemaRoot.syncSchema());
      assertEquals(newSchema, schemaRoot.getSchema());

      // no schema update this time.
      assertFalse(schemaRoot.syncSchema());
    }
  }
}
