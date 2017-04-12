/*
 * The MIT License
 *
 * Copyright (c) 2016 CloudBees, Inc.
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
 *
 */

package jenkins.scm.impl.mock;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Action;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMNavigatorDescriptor;
import jenkins.scm.api.SCMNavigatorEvent;
import jenkins.scm.api.SCMNavigatorOwner;
import jenkins.scm.api.SCMSourceObserver;
import jenkins.scm.api.metadata.ObjectMetadataAction;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public class MockSCMNavigator extends SCMNavigator {

    private final String controllerId;
    private final List<SCMSourceTrait> traits;
    private transient MockSCMController controller;

    @DataBoundConstructor
    public MockSCMNavigator(String controllerId, List<SCMSourceTrait> traits) {
        this.controllerId = controllerId;
        this.traits = new ArrayList<SCMSourceTrait>(traits);
    }

    public MockSCMNavigator(String controllerId, SCMSourceTrait... traits) {
        this(controllerId, Arrays.asList(traits));
    }

    public MockSCMNavigator(MockSCMController controller, List<SCMSourceTrait> traits) {
        this.controllerId = controller.getId();
        this.controller = controller;
        this.traits = new ArrayList<SCMSourceTrait>(traits);
    }

    public MockSCMNavigator(MockSCMController controller, SCMSourceTrait... traits) {
        this(controller, Arrays.asList(traits));
    }

    public String getControllerId() {
        return controllerId;
    }

    private MockSCMController controller() {
        if (controller == null) {
            controller = MockSCMController.lookup(controllerId);
        }
        return controller;
    }

    public List<SCMSourceTrait> getTraits() {
        return Collections.unmodifiableList(traits);
    }

    @Override
    protected String id() {
        return controllerId;
    }

    @Override
    public void visitSources(@NonNull SCMSourceObserver observer) throws IOException, InterruptedException {
        controller().applyLatency();
        controller().checkFaults(null, null, null, false);
        Set<String> includes = observer.getIncludes();
        for (String name : controller().listRepositories()) {
            if (!observer.isObserving()) {
                return;
            }
            checkInterrupt();
            if (includes != null && !includes.contains(name)) {
                continue;
            }
            controller().applyLatency();
            controller().checkFaults(name, null, null, false);
            SCMSourceObserver.ProjectObserver po = observer.observe(name);
            po.addSource(new MockSCMSource(getId() + "::" + name, controller, name, traits));
            po.complete();
        }
    }

    @NonNull
    @Override
    public List<Action> retrieveActions(@NonNull SCMNavigatorOwner owner,
                                        @CheckForNull SCMNavigatorEvent event,
                                        @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        controller().applyLatency();
        controller().checkFaults(null, null, null, true);
        List<Action> result = new ArrayList<Action>();
        result.add(new MockSCMLink("organization"));
        String description = controller().getDescription();
        String displayName = controller().getDisplayName();
        String url = controller().getUrl();
        String iconClassName = controller().getOrgIconClassName();
        if (description != null || displayName != null || url != null) {
            result.add(new ObjectMetadataAction(displayName, description, url));
        }
        if (iconClassName != null) {
            result.add(new MockAvatarMetadataAction(iconClassName));
        }
        return result;
    }

    @Extension
    public static class DescriptorImpl extends SCMNavigatorDescriptor {

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Mock SCM";
        }

        @Override
        public SCMNavigator newInstance(@CheckForNull String name) {
            return null;
        }

        public ListBoxModel doFillControllerIdItems() {
            ListBoxModel result = new ListBoxModel();
            for (MockSCMController c : MockSCMController.all()) {
                result.add(c.getId());
            }
            return result;
        }

        public List<SCMSourceTraitDescriptor> getTraitDescriptors() {
            MockSCM.DescriptorImpl scmDescriptor =
                    ExtensionList.lookup(Descriptor.class).get(MockSCM.DescriptorImpl.class);
            List<SCMSourceTraitDescriptor> result = new ArrayList<SCMSourceTraitDescriptor>();
            for (Descriptor<SCMSourceTrait> d : Jenkins.getActiveInstance().getDescriptorList(SCMSourceTrait.class)) {
                if (d instanceof SCMSourceTraitDescriptor) {
                    SCMSourceTraitDescriptor descriptor = (SCMSourceTraitDescriptor) d;
                    if (!descriptor.isApplicableTo(
                            scmDescriptor)) {
                        continue;
                    }
                    if (!descriptor.isApplicableTo(MockSCMSourceRequestBuilder.class)) {
                        continue;
                    }
                    result.add(descriptor);
                }
            }
            return result;
        }

        public List<SCMSourceTrait> getDefaultTraits() {
            return Collections.<SCMSourceTrait>singletonList(new MockSCMDiscoverBranches());
        }
    }
}
