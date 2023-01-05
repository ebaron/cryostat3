/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.targets;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EntityListeners;
import javax.persistence.OneToMany;
import javax.persistence.PostPersist;
import javax.persistence.PostRemove;
import javax.persistence.PostUpdate;

import io.cryostat.recordings.ActiveRecording;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkiverse.hibernate.types.json.JsonBinaryType;
import io.quarkiverse.hibernate.types.json.JsonTypes;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.core.eventbus.EventBus;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

@Entity
@EntityListeners(Target.Listener.class)
@TypeDef(name = JsonTypes.JSON_BIN, typeClass = JsonBinaryType.class)
public class Target extends PanacheEntity {

    static final String TARGET_JVM_DISCOVERY = "TargetJvmDiscovery";

    @Column(unique = true, nullable = false, updatable = false)
    public URI connectUrl;

    @Column(unique = true, nullable = false)
    public String alias;

    public String jvmId;

    @Type(type = JsonTypes.JSON_BIN)
    @Column(columnDefinition = JsonTypes.JSON_BIN, nullable = false)
    public Map<String, String> labels = new HashMap<>();

    @Type(type = JsonTypes.JSON_BIN)
    @Column(columnDefinition = JsonTypes.JSON_BIN, nullable = false)
    public Annotations annotations = new Annotations();

    @OneToMany(
            mappedBy = "target",
            cascade = {CascadeType.ALL},
            orphanRemoval = true)
    public List<ActiveRecording> activeRecordings = new ArrayList<>();

    public static Target getTargetByConnectUrl(URI connectUrl) {
        return find("connectUrl", connectUrl).singleResult();
    }

    public static boolean deleteByConnectUrl(URI connectUrl) {
        return delete("connectUrl", connectUrl) > 0;
    }

    public static class Annotations {
        public Map<String, String> platform;
        public Map<String, String> cryostat;

        public Annotations() {
            this.platform = new HashMap<>();
            this.cryostat = new HashMap<>();
        }

        @Override
        public int hashCode() {
            return Objects.hash(cryostat, platform);
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
            Annotations other = (Annotations) obj;
            return Objects.equals(cryostat, other.cryostat)
                    && Objects.equals(platform, other.platform);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(alias, annotations, connectUrl, jvmId, labels);
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
        Target other = (Target) obj;
        return Objects.equals(alias, other.alias)
                && Objects.equals(annotations, other.annotations)
                && Objects.equals(connectUrl, other.connectUrl)
                && Objects.equals(jvmId, other.jvmId)
                && Objects.equals(labels, other.labels);
    }

    public enum EventKind {
        FOUND,
        MODIFIED,
        LOST,
        ;
    }

    public record TargetDiscovery(EventKind kind, Target serviceRef) {}

    @ApplicationScoped
    static class Listener {

        @Inject EventBus bus;

        @PostPersist
        void postPersist(Target target) {
            notify(EventKind.FOUND, target);
        }

        @PostUpdate
        void postUpdate(Target target) {
            notify(EventKind.MODIFIED, target);
        }

        @PostRemove
        void postRemove(Target target) {
            notify(EventKind.LOST, target);
        }

        private void notify(EventKind eventKind, Target target) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            TARGET_JVM_DISCOVERY,
                            new TargetDiscoveryEvent(new TargetDiscovery(eventKind, target))));
            bus.publish(TARGET_JVM_DISCOVERY, new TargetDiscovery(eventKind, target));
        }

        public record TargetDiscoveryEvent(TargetDiscovery event) {}
    }
}
