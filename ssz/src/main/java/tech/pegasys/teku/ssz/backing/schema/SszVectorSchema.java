/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.ssz.backing.schema;

import static java.util.Collections.emptyList;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.ssz.backing.SszData;
import tech.pegasys.teku.ssz.backing.SszVector;
import tech.pegasys.teku.ssz.backing.schema.SszSchemaHints.SszSuperNodeHint;
import tech.pegasys.teku.ssz.backing.tree.LeafNode;
import tech.pegasys.teku.ssz.backing.tree.SszSuperNode;
import tech.pegasys.teku.ssz.backing.tree.TreeNode;
import tech.pegasys.teku.ssz.backing.tree.TreeUtil;
import tech.pegasys.teku.ssz.backing.view.SszVectorImpl;
import tech.pegasys.teku.ssz.sos.SszDeserializeException;
import tech.pegasys.teku.ssz.sos.SszLengthBounds;
import tech.pegasys.teku.ssz.sos.SszReader;
import tech.pegasys.teku.ssz.sos.SszWriter;

public class SszVectorSchema<SszElementT extends SszData>
    extends SszCollectionSchema<SszElementT, SszVector<SszElementT>> {

  private final boolean isListBacking;

  public SszVectorSchema(SszSchema<SszElementT> elementType, long vectorLength) {
    this(elementType, vectorLength, false);
  }

  SszVectorSchema(SszSchema<SszElementT> elementType, long vectorLength, boolean isListBacking) {
    this(elementType, vectorLength, isListBacking, SszSchemaHints.none());
  }

  SszVectorSchema(
      SszSchema<SszElementT> elementSchema,
      long vectorLength,
      boolean isListBacking,
      SszSchemaHints hints) {
    super(vectorLength, elementSchema, hints);
    this.isListBacking = isListBacking;
  }

  @Override
  public SszVector<SszElementT> getDefault() {
    return createFromBackingNode(getDefaultTree());
  }

  @Override
  protected TreeNode createDefaultTree() {
    if (isListBacking) {
      Optional<SszSuperNodeHint> sszSuperNodeHint = getHints().getHint(SszSuperNodeHint.class);
      if (sszSuperNodeHint.isPresent()) {
        int superNodeDepth = sszSuperNodeHint.get().getDepth();
        SszSuperNode defaultSuperSszNode =
            new SszSuperNode(superNodeDepth, elementSszSupernodeTemplate.get(), Bytes.EMPTY);
        int binaryDepth = treeDepth() - superNodeDepth;
        return TreeUtil.createTree(emptyList(), defaultSuperSszNode, binaryDepth);
      } else {
        return TreeUtil.createDefaultTree(maxChunks(), LeafNode.EMPTY_LEAF);
      }
    } else if (getElementSchema().getBitsSize() == LeafNode.MAX_BIT_SIZE) {
      return TreeUtil.createDefaultTree(maxChunks(), getElementSchema().getDefaultTree());
    } else {
      // packed vector
      int totalBytes = (getLength() * getElementSchema().getBitsSize() + 7) / 8;
      int lastNodeSizeBytes = totalBytes % LeafNode.MAX_BYTE_SIZE;
      int fullZeroNodesCount = totalBytes / LeafNode.MAX_BYTE_SIZE;
      Stream<TreeNode> fullZeroNodes =
          Stream.<TreeNode>generate(() -> LeafNode.ZERO_LEAVES[32]).limit(fullZeroNodesCount);
      Stream<TreeNode> lastZeroNode =
          lastNodeSizeBytes > 0
              ? Stream.of(LeafNode.ZERO_LEAVES[lastNodeSizeBytes])
              : Stream.empty();
      return TreeUtil.createTree(
          Stream.concat(fullZeroNodes, lastZeroNode).collect(Collectors.toList()));
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Override
  public SszVector<SszElementT> createFromBackingNode(TreeNode node) {
    return new SszVectorImpl(this, node);
  }

  public int getLength() {
    long maxLength = getMaxLength();
    if (maxLength > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Vector size too large: " + maxLength);
    }
    return (int) maxLength;
  }

  public int getChunksCount() {
    long maxChunks = maxChunks();
    if (maxChunks > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Vector size too large: " + maxChunks);
    }
    return (int) maxChunks;
  }

  @Override
  public boolean isFixedSize() {
    return getElementSchema().isFixedSize();
  }

  @Override
  public int getVariablePartSize(TreeNode node) {
    return getVariablePartSize(node, getLength());
  }

  @Override
  public int getFixedPartSize() {
    int bitsPerChild = isFixedSize() ? getElementSchema().getBitsSize() : SSZ_LENGTH_SIZE * 8;
    return (getLength() * bitsPerChild + 7) / 8;
  }

  @Override
  public int sszSerializeTree(TreeNode node, SszWriter writer) {
    return sszSerializeVector(node, writer, getLength());
  }

  @Override
  public TreeNode sszDeserializeTree(SszReader reader) {
    DeserializedData data = sszDeserializeVector(reader);
    if (getElementSchema() == SszPrimitiveSchemas.BIT_SCHEMA && getLength() % 8 > 0) {
      // for BitVector we need to check that all 'unused' bits in the last byte are 0
      int usedBitCount = getLength() % 8;
      if (data.getLastSszByte().orElseThrow() >>> usedBitCount != 0) {
        throw new SszDeserializeException("Invalid Bitvector ssz: trailing bits are not 0");
      }
    } else {
      if (data.getChildrenCount() != getLength()) {
        throw new SszDeserializeException("Invalid Vector ssz");
      }
    }
    return data.getDataTree();
  }

  @Override
  public SszLengthBounds getSszLengthBounds() {
    return getElementSchema()
        .getSszLengthBounds()
        // if elements are of dynamic size the offset size should be added for every element
        .addBytes(getElementSchema().isFixedSize() ? 0 : SSZ_LENGTH_SIZE)
        .mul(getLength())
        .ceilToBytes();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SszVectorSchema)) {
      return false;
    }
    SszVectorSchema<?> that = (SszVectorSchema<?>) o;
    return getElementSchema().equals(that.getElementSchema())
        && getMaxLength() == that.getMaxLength();
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }

  @Override
  public String toString() {
    return "Vector[" + getElementSchema() + ", " + getLength() + "]";
  }
}
