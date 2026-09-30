package com.ahdyahmed.pymk.domain.vector;

/**
 * Formats a {@code float[]} as a pgvector text literal, e.g. {@code
 * "[0.12,-0.4,...]"}, so it can be bound as a plain JDBC string parameter and
 * cast server-side with {@code ?::vector} / {@code CAST(? AS vector)}.
 *
 * <p>Deliberately avoids requiring a pgvector JDBC type-registration step
 * (like {@code PGvector.addVectorType(connection)}): binding text and
 * casting in SQL works with a completely ordinary {@code PreparedStatement},
 * which keeps {@code BulkLoader} (pymk-datagen) and any ad-hoc native query
 * free of extra driver-level setup.</p>
 */
public final class VectorLiterals {

    private VectorLiterals() {
    }

    public static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
