/*
 * The MIT License
 *
 * Copyright (c) 2011-2013, CloudBees, Inc., Stephen Connolly.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.scm.api;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.SCM;
import hudson.scm.SCMRevisionState;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A virtual file system for a specific {@link SCM} potentially pinned to a specific {@link SCMRevision}. In contrast
 * to {@link SCMProbe}, implementations should not cache results (while some DVCS implementations may need to perform
 * a local checkout in order to be able to implement this API it should be noted that in such cases the local checkout
 * is not a cache but rather a copy of the immutable revisions - this may look and sound like a cache but it isn't
 * as the revision itself is immutable.
 *
 * @author Stephen Connolly
 */
public abstract class SCMFileSystem implements Closeable {

    @CheckForNull
    private final SCMRevision rev;

    protected SCMFileSystem(@CheckForNull SCMRevision rev) {
        this.rev = rev;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        // no-op
    }

    /**
     * Returns the time that the {@link SCMFileSystem} was last modified. This should logically be equivalent to the
     * maximum {@link SCMFile#lastModified()} that you would find if you were to do the horribly inefficient traversal
     * of all the {@link SCMFile} instances from {@link #getRoot()}. Where implementers do not have an easy and quick
     * way to get this information (such as by looking at the commit time of the {@link #getRevision()} HINT HINT)
     * then just return {@code 0L}.
     *
     * @return A <code>long</code> value representing the time the {@link SCMFileSystem} was
     * last modified, measured in milliseconds since the epoch
     * (00:00:00 GMT, January 1, 1970) or {@code 0L} if the operation is unsupported.
     * @throws IOException if an error occurs while performing the operation.
     */
    public abstract long lastModified() throws IOException;

    /**
     * If this inspector is looking at the specific commit,
     * returns that revision. Otherwise null, indicating
     * that the inspector is looking at just the latest state
     * of the repository.
     *
     * @return the revision of the commit the inspector is looking at, or null if none.
     */
    @CheckForNull
    public SCMRevision getRevision() {
        return rev;
    }

    /**
     * Whether this inspector is looking at the specific commit.
     * <p>Short for {@code getRevision()!=null}.</p>.
     *
     * @return true if this inspector is looking at the specific commit.
     */
    public final boolean isFixedRevision() {
        return getRevision() != null;
    }

    /**
     * Short for {@code getRoot().child(path)}.
     *
     * @param path Path of the SCMFile to obtain from the root of the repository.
     * @return null if there's no file/directory at the requested path.
     */
    @NonNull
    public final SCMFile child(@NonNull String path) {
        return getRoot().child(path);
    }

    /**
     * Returns the {@link SCMFile} object that represents the root directory of the repository.
     *
     * @return the root directory of the repository.
     */
    @NonNull
    public abstract SCMFile getRoot();

    /**
     * Writes the changes between the specified revision and {@link #getRevision()} in the format compatible
     * with the {@link SCM} from this {@link SCMFileSystem#of(SCM)} to the supplied {@link OutputStream}.
     * This method allows for consumers or the SCM API to replicate the
     * {@link SCM#checkout(Run, Launcher, FilePath, TaskListener, File, SCMRevisionState)} functionality
     * that captures the changelog without requiring a full checkout.
     *
     * @param revision        the starting revision or {@code null} to capture the initial change set.
     * @param changeLogStream the destination to stream the changes to.
     * @throws UnsupportedOperationException if this {@link SCMFileSystem} does not support changelog querying.
     * @since FIXME
     */
    public boolean changesSince(@CheckForNull SCMRevision revision, @NonNull OutputStream changeLogStream)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Given a {@link SCM} this method will try to retrieve a corresponding {@link SCMFileSystem} instance.
     *
     * @param scm the {@link SCM}.
     * @return the corresponding {@link SCMFileSystem} or {@code null} if there is none.
     */
    @CheckForNull
    public static SCMFileSystem of(@NonNull SCM scm) {
        return of(scm, null);
    }

    /**
     * Given a {@link SCM} this method will try to retrieve a corresponding {@link SCMFileSystem} instance that
     * reflects the content at the specified {@link SCMRevision}.
     *
     * @param scm the {@link SCM}.
     * @param rev the specified {@link SCMRevision}.
     * @return the corresponding {@link SCMFileSystem} or {@code null} if there is none.
     */
    @CheckForNull
    public static SCMFileSystem of(@NonNull SCM scm, @CheckForNull SCMRevision rev) {
        scm.getClass(); // throw NPE if null
        SCMFileSystem fallBack = null;
        for (Builder b : ExtensionList.lookup(Builder.class)) {
            if (b.supports(scm)) {
                SCMFileSystem inspector = b.build(scm, rev);
                if (inspector != null) {
                    if (inspector.isFixedRevision()) {
                        return inspector;
                    }
                    if (fallBack == null) {
                        fallBack = inspector;
                    }
                }
            }
        }
        return fallBack;
    }


    /**
     * Given a {@link SCM} this method will check if there is at least one {@link SCMFileSystem} provider capable
     * of being instantiated. Returning {@code true} does not mean that {@link #of(SCM, SCMRevision)}
     * will be able to instantiate a {@link SCMFileSystem} for any specific {@link SCMRevision},
     * rather returning {@code false} indicates that there is absolutely no point in calling
     * {@link #of(SCM, SCMRevision)} as it will always return {@code null}.
     *
     * @param scm the {@link SCMSource}.
     * @return {@code true} if and only if the supplied {@link SCM} is supported by at least one {@link Builder}.
     * @since FIXME
     */
    public static boolean supports(@NonNull SCM scm) {
        scm.getClass(); // throw NPE if null
        for (Builder b : ExtensionList.lookup(Builder.class)) {
            if (b.supports(scm)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Given a {@link SCMSource} and a {@link SCMHead} this method will try to retrieve a corresponding
     * {@link SCMFileSystem} instance that reflects the content of the specified {@link SCMHead}.
     *
     * @param source the {@link SCMSource}.
     * @param head   the specified {@link SCMHead}.
     * @return the corresponding {@link SCMFileSystem} or {@code null} if there is none.
     */
    @CheckForNull
    public static SCMFileSystem of(@NonNull SCMSource source, @NonNull SCMHead head) {
        return of(source, head, null);
    }

    /**
     * Given a {@link SCMSource}, a {@link SCMHead} and a {@link SCMRevision} this method will try to retrieve a
     * corresponding {@link SCMFileSystem} instance that reflects the content of the specified {@link SCMHead} at the
     * specified {@link SCMRevision}.
     *
     * @param source the {@link SCMSource}.
     * @param head   the specified {@link SCMHead}.
     * @param rev    the specified {@link SCMRevision}.
     * @return the corresponding {@link SCMFileSystem} or {@code null} if there is none.
     */
    @CheckForNull
    public static SCMFileSystem of(@NonNull SCMSource source, @NonNull SCMHead head,
                                   @CheckForNull SCMRevision rev) {
        source.getClass(); // throw NPE if null
        SCMFileSystem fallBack = null;
        for (Builder b : ExtensionList.lookup(Builder.class)) {
            if (b.supports(source)) {
                SCMFileSystem inspector = b.build(source, head, rev);
                if (inspector != null) {
                    if (inspector.isFixedRevision()) {
                        return inspector;
                    }
                    if (fallBack == null) {
                        fallBack = inspector;
                    }
                }
            }
        }
        return fallBack;
    }

    /**
     * Given a {@link SCMSource} this method will check if there is at least one {@link SCMFileSystem} provider capable
     * of being instantiated. Returning {@code true} does not mean that {@link #of(SCMSource, SCMHead, SCMRevision)}
     * will be able to instantiate a {@link SCMFileSystem} for any specific {@link SCMHead} or {@link SCMRevision},
     * rather returning {@code false} indicates that there is absolutely no point in calling
     * {@link #of(SCMSource, SCMHead, SCMRevision)} as it will always return {@code null}.
     *
     * @param source the {@link SCMSource}.
     * @return {@code true} if and only if the supplied {@link SCMSource} is supported by at least one {@link Builder}.
     * @since FIXME
     */
    public static boolean supports(@NonNull SCMSource source) {
        source.getClass(); // throw NPE if null
        for (Builder b : ExtensionList.lookup(Builder.class)) {
            if (b.supports(source)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extension point that allows different plugins to implement {@link SCMFileSystem} classes for the same {@link SCM}
     * or {@link SCMSource} and let Jenkins pick the most capable for any specific {@link SCM} implementation.
     */
    public abstract static class Builder implements ExtensionPoint {

        /**
         * Checks if this {@link Builder} supports the supplied {@link SCM}.
         *
         * @param source the {@link SCM}.
         * @return {@code true} if and only if the supplied {@link SCM} is supported by this {@link Builder}.
         */
        public abstract boolean supports(SCM source);

        /**
         * Checks if this {@link Builder} supports the supplied {@link SCMSource}.
         *
         * @param source the {@link SCMSource}.
         * @return {@code true} if and only if the supplied {@link SCMSource} is supported by this {@link Builder}.
         */
        public abstract boolean supports(SCMSource source);

        /**
         * Given a {@link SCM} this should try to build a corresponding {@link SCMFileSystem} instance that
         * reflects the content at the specified {@link SCMRevision}. If the {@link SCM} is supported but not
         * for a fixed revision, best effort is acceptable as the most capable {@link SCMFileSystem} will be returned
         * to the caller.
         *
         * @param scm the {@link SCM}.
         * @param rev the specified {@link SCMRevision}.
         * @return the corresponding {@link SCMFileSystem} or {@code null} if this builder cannot create a {@link
         * SCMFileSystem} for the specified {@link SCM}.
         */
        @CheckForNull
        public abstract SCMFileSystem build(@NonNull SCM scm, @CheckForNull SCMRevision rev);

        /**
         * Given a {@link SCMSource}, a {@link SCMHead} and a {@link SCMRevision} this method should try to build a
         * corresponding {@link SCMFileSystem} instance that reflects the content of the specified {@link SCMHead} at
         * the specified {@link SCMRevision}. If the {@link SCMSource} is supported but not for a fixed revision,
         * best effort is acceptable as the most capable {@link SCMFileSystem} will be returned
         * to the caller.
         *
         * @param source the {@link SCMSource}.
         * @param head   the specified {@link SCMHead}.
         * @param rev    the specified {@link SCMRevision}.
         * @return the corresponding {@link SCMFileSystem} or {@code null} if there is none.
         */
        @CheckForNull
        public SCMFileSystem build(@NonNull SCMSource source, @NonNull SCMHead head,
                                   @CheckForNull SCMRevision rev) {
            return build(source.build(head, rev), rev);
        }
    }
}
