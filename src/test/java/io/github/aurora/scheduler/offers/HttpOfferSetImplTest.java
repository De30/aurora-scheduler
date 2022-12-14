/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.aurora.scheduler.offers;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.apache.aurora.common.testing.easymock.EasyMockTest;
import org.apache.aurora.gen.AssignedTask;
import org.apache.aurora.gen.HostAttributes;
import org.apache.aurora.gen.ScheduleStatus;
import org.apache.aurora.gen.ScheduledTask;
import org.apache.aurora.scheduler.base.TaskGroupKey;
import org.apache.aurora.scheduler.base.TaskTestUtil;
import org.apache.aurora.scheduler.filter.SchedulingFilter;
import org.apache.aurora.scheduler.offers.HostOffer;
import org.apache.aurora.scheduler.offers.Offers;
import org.apache.aurora.scheduler.storage.Storage;
import org.apache.aurora.scheduler.storage.entities.IHostAttributes;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;
import org.apache.aurora.scheduler.storage.mem.MemStorageModule;
import org.junit.Before;
import org.junit.Test;

import static org.apache.aurora.gen.MaintenanceMode.NONE;
import static org.apache.aurora.scheduler.base.TaskTestUtil.JOB;
import static org.apache.aurora.scheduler.base.TaskTestUtil.makeTask;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HttpOfferSetImplTest extends EasyMockTest {

  private static final String HOST_A = "HOST_A";
  private static final IHostAttributes HOST_ATTRIBUTES_A =
      IHostAttributes.build(new HostAttributes().setMode(NONE).setHost(HOST_A));
  private static final HostOffer OFFER_A = new HostOffer(
      Offers.makeOffer("OFFER_A", HOST_A),
      HOST_ATTRIBUTES_A);
  private static final String HOST_B = "HOST_B";
  private static final IHostAttributes HOST_ATTRIBUTES_B =
          IHostAttributes.build(new HostAttributes().setMode(NONE).setHost(HOST_B));
  private static final HostOffer OFFER_B = new HostOffer(
      Offers.makeOffer("OFFER_B", HOST_B),
          HOST_ATTRIBUTES_B);
  private static final String HOST_C = "HOST_C";
  private static final HostOffer OFFER_C = new HostOffer(
      Offers.makeOffer("OFFER_C", HOST_C),
      IHostAttributes.build(new HostAttributes().setMode(NONE).setHost(HOST_C)));
  private static final HostOffer OFFER_C1 = new HostOffer(
          Offers.makeOffer("OFFER_C1", HOST_C),
          IHostAttributes.build(new HostAttributes().setMode(NONE).setHost(HOST_C)));
  private static final String HOST_D = "HOST_D";

  private final Storage storage = MemStorageModule.newEmptyStorage();

  private HttpOfferSetImpl httpOfferSet;
  private Set<HostOffer> offers;
  private HttpOfferSetImpl duplicateHostsHttpOfferSet;

  @Before
  public void setUp() throws IOException {
    storage.write((Storage.MutateWork.NoResult.Quiet) sp -> {
      ScheduledTask t0 = makeTask("t0", JOB)
          .newBuilder()
          .setStatus(ScheduleStatus.PENDING);
      ScheduledTask t1 = makeTask("t1", JOB)
          .newBuilder()
          .setStatus(ScheduleStatus.STARTING);
      t1.setAssignedTask(new AssignedTask("t1",
              OFFER_B.getOffer().getAgentId().getValue(),
              OFFER_B.getOffer().getHostname(),
              t1.getAssignedTask().getTask(),
              new HashMap<>(),
              0));

      ScheduledTask t2 = makeTask("t2", JOB)
          .newBuilder()
          .setStatus(ScheduleStatus.RUNNING);
      t2.setAssignedTask(new AssignedTask("t2",
          OFFER_C.getOffer().getAgentId().getValue(),
          OFFER_C.getOffer().getHostname(),
          t1.getAssignedTask().getTask(),
          new HashMap<>(),
          0));

      sp.getUnsafeTaskStore().saveTasks(
          IScheduledTask.setFromBuilders(ImmutableList.of(t0, t1, t2)));
    });

    offers = new HashSet<>();
    offers.add(OFFER_A);
    offers.add(OFFER_B);
    offers.add(OFFER_C);

    httpOfferSet = new HttpOfferSetImpl(offers,
        0,
        new URL("http://localhost:9090/v1/offerset"),
        0,
        0,
        false);

    // duplicate host offers
    Set<HostOffer> duplicateHostOffers = new HashSet<>();
    duplicateHostOffers.add(OFFER_A);
    duplicateHostOffers.add(OFFER_B);
    duplicateHostOffers.add(OFFER_C);
    duplicateHostOffers.add(OFFER_C1);

    duplicateHostsHttpOfferSet = new HttpOfferSetImpl(duplicateHostOffers,
            0,
            new URL("http://localhost:9090/v1/offerset"),
            0,
            0,
            false);
  }

  @Test
  public void testProcessResponse() throws IOException {
    control.replay();
    String responseStr = "{\"error\": \"\", \"hosts\": [\""
            + HOST_A + "\",\""
            + HOST_B + "\",\""
            + HOST_C + "\"]}";

    List<HostOffer> mOffers = ImmutableList.copyOf(httpOfferSet.values());

    List<HostOffer> sortedOffers = httpOfferSet.processResponse(mOffers, responseStr, 0);
    assertEquals(sortedOffers.size(), 3);
    assertEquals(sortedOffers.get(0).getAttributes().getHost(), HOST_A);
    assertEquals(sortedOffers.get(1).getAttributes().getHost(), HOST_B);
    assertEquals(sortedOffers.get(2).getAttributes().getHost(), HOST_C);
    assertEquals((long) HttpOfferSetImpl.offerSetDiffList.get(0), 0);

    // plugin returns less offers than Aurora has.
    responseStr = "{\"error\": \"\", \"hosts\": [\""
            + HOST_A + "\",\""
            + HOST_C + "\"]}";
    sortedOffers = httpOfferSet.processResponse(mOffers, responseStr, 0);
    assertEquals(sortedOffers.size(), 2);
    assertEquals(sortedOffers.get(0).getAttributes().getHost(), HOST_A);
    assertEquals(sortedOffers.get(1).getAttributes().getHost(), HOST_C);
    assertEquals((long) HttpOfferSetImpl.offerSetDiffList.get(1), 1);

    // plugin returns more offers than Aurora has.
    responseStr = "{\"error\": \"\", \"hosts\": [\""
            + HOST_A + "\",\""
            + HOST_B + "\",\""
            + HOST_D + "\",\""
            + HOST_C + "\"]}";
    sortedOffers = httpOfferSet.processResponse(mOffers, responseStr, 0);
    assertEquals(sortedOffers.size(), 3);
    assertEquals(sortedOffers.get(0).getAttributes().getHost(), HOST_A);
    assertEquals(sortedOffers.get(1).getAttributes().getHost(), HOST_B);
    assertEquals(sortedOffers.get(2).getAttributes().getHost(), HOST_C);
    assertEquals((long) HttpOfferSetImpl.offerSetDiffList.get(2), 1);

    // plugin omits 1 offer & returns 1 extra offer
    responseStr = "{\"error\": \"\", \"hosts\": [\""
        + HOST_A + "\",\""
        + HOST_D + "\",\""
        + HOST_C + "\"]}";
    sortedOffers = httpOfferSet.processResponse(mOffers, responseStr, 0);
    assertEquals(sortedOffers.size(), 2);
    assertEquals(sortedOffers.get(0).getAttributes().getHost(), HOST_A);
    assertEquals(sortedOffers.get(1).getAttributes().getHost(), HOST_C);
    assertEquals((long) HttpOfferSetImpl.offerSetDiffList.get(3), 2);

    // Test with 1 bad offer
    sortedOffers = httpOfferSet.processResponse(mOffers, responseStr, 1);
    assertEquals(sortedOffers.size(), 2);
    assertEquals(sortedOffers.get(0).getAttributes().getHost(), HOST_A);
    assertEquals(sortedOffers.get(1).getAttributes().getHost(), HOST_C);
    assertEquals((long) HttpOfferSetImpl.offerSetDiffList.get(4), 3);

    responseStr = "{\"error\": \"Error\", \"hosts\": [\""
            + HOST_A + "\",\""
            + HOST_B + "\",\""
            + HOST_C + "\"]}";
    boolean isException = false;
    try {
      httpOfferSet.processResponse(mOffers, responseStr, 0);
    } catch (IOException ioe) {
      isException = true;
    }
    assertTrue(isException);

    responseStr = "{\"error\": \"error\"}";
    isException = false;
    try {
      httpOfferSet.processResponse(mOffers, responseStr, 0);
    } catch (IOException ioe) {
      isException = true;
    }
    assertTrue(isException);

    responseStr = "{\"weird\": \"cannot decode this json string\"}";
    isException = false;
    try {
      httpOfferSet.processResponse(mOffers, responseStr, 0);
    } catch (IOException ioe) {
      isException = true;
    }
    assertTrue(isException);

    // Duplicate host test
    responseStr = "{\"error\": \"\", \"hosts\": [\""
            + HOST_A + "\",\""
            + HOST_B + "\",\""
            + HOST_C + "\"]}";

    List<HostOffer> mDuplicateHostOffers = ImmutableList.
            copyOf(duplicateHostsHttpOfferSet.values());
    assertEquals(mDuplicateHostOffers.size(), 4);

    sortedOffers = duplicateHostsHttpOfferSet.processResponse(mDuplicateHostOffers,
            responseStr, 0);
    assertEquals(sortedOffers.size(), 4);
    assertEquals(sortedOffers.get(0).getAttributes().getHost(), HOST_A);
    assertEquals(sortedOffers.get(1).getAttributes().getHost(), HOST_B);
    assertEquals(sortedOffers.get(2).getAttributes().getHost(), HOST_C);
    assertEquals(sortedOffers.get(3).getAttributes().getHost(), HOST_C);
    assertEquals((long) HttpOfferSetImpl.offerSetDiffList.get(5), 0);

    // plugin returns less offers than Aurora has.
    responseStr = "{\"error\": \"\", \"hosts\": [\""
            + HOST_A + "\",\""
            + HOST_C + "\"]}";
    sortedOffers = duplicateHostsHttpOfferSet.processResponse(mDuplicateHostOffers, responseStr, 0);
    assertEquals(sortedOffers.size(), 3);
    assertEquals(sortedOffers.get(0).getAttributes().getHost(), HOST_A);
    assertEquals(sortedOffers.get(1).getAttributes().getHost(), HOST_C);
    assertEquals((long) HttpOfferSetImpl.offerSetDiffList.get(6), 1);

    // plugin returns more offers than Aurora has.
    responseStr = "{\"error\": \"\", \"hosts\": [\""
            + HOST_A + "\",\""
            + HOST_B + "\",\""
            + HOST_D + "\",\""
            + HOST_C + "\"]}";
    sortedOffers = duplicateHostsHttpOfferSet.processResponse(mDuplicateHostOffers, responseStr, 0);
    assertEquals(sortedOffers.size(), 4);
    assertEquals(sortedOffers.get(0).getAttributes().getHost(), HOST_A);
    assertEquals(sortedOffers.get(1).getAttributes().getHost(), HOST_B);
    assertEquals(sortedOffers.get(2).getAttributes().getHost(), HOST_C);
    assertEquals((long) HttpOfferSetImpl.offerSetDiffList.get(7), 1);

    // plugin omits 1 offer & returns 1 extra offer
    responseStr = "{\"error\": \"\", \"hosts\": [\""
            + HOST_A + "\",\""
            + HOST_D + "\",\""
            + HOST_B + "\"]}";
    sortedOffers = duplicateHostsHttpOfferSet.processResponse(mDuplicateHostOffers, responseStr, 0);
    assertEquals(sortedOffers.size(), 2);
    assertEquals(sortedOffers.get(0).getAttributes().getHost(), HOST_A);
    assertEquals(sortedOffers.get(1).getAttributes().getHost(), HOST_B);
    assertEquals((long) HttpOfferSetImpl.offerSetDiffList.get(8), 3);

    // Test with 1 bad offer
    sortedOffers = duplicateHostsHttpOfferSet.processResponse(mDuplicateHostOffers, responseStr, 1);
    assertEquals(sortedOffers.size(), 2);
    assertEquals(sortedOffers.get(0).getAttributes().getHost(), HOST_A);
    assertEquals(sortedOffers.get(1).getAttributes().getHost(), HOST_B);
    assertEquals((long) HttpOfferSetImpl.offerSetDiffList.get(9), 4);

    responseStr = "{\"error\": \"Error\", \"hosts\": [\""
            + HOST_A + "\",\""
            + HOST_B + "\",\""
            + HOST_C + "\"]}";
    isException = false;
    try {
      duplicateHostsHttpOfferSet.processResponse(mOffers, responseStr, 0);
    } catch (IOException ioe) {
      isException = true;
    }
    assertTrue(isException);
  }

  @Test
  public void testGetOrdered() throws IOException {
    control.replay();
    IScheduledTask task = makeTask("id", JOB);
    TaskGroupKey groupKey = TaskGroupKey.from(task.getAssignedTask().getTask());
    SchedulingFilter.ResourceRequest resourceRequest =
        TaskTestUtil.toResourceRequest(task.getAssignedTask().getTask());
    HttpOfferSetImpl.fetchStartingTasks(storage);

    // return the same set of offers
    Iterable<HostOffer> sortedOffers = httpOfferSet.getOrdered(groupKey, resourceRequest);
    assertEquals(offers.size(), Iterables.size(sortedOffers));

    httpOfferSet = new HttpOfferSetImpl(offers,
        0,
        new URL("http://localhost:9090/v1/offerset"),
        0,
        -1,
        false);
    sortedOffers = httpOfferSet.getOrdered(groupKey, resourceRequest);
    assertEquals(offers.size(), Iterables.size(sortedOffers));

    httpOfferSet = new HttpOfferSetImpl(offers,
        0,
        new URL("http://localhost:9090/v1/offerset"),
        0,
        2,
        false);
    sortedOffers = httpOfferSet.getOrdered(groupKey, resourceRequest);
    assertEquals(offers.size(), Iterables.size(sortedOffers));

    // OFFER_B is put in the bottom of list as it has 1 starting task.
    httpOfferSet = new HttpOfferSetImpl(offers,
        0,
        new URL("http://localhost:9090/v1/offerset"),
        0,
        1,
        false);
    sortedOffers = httpOfferSet.getOrdered(groupKey, resourceRequest);
    assertEquals(offers.size(), Iterables.size(sortedOffers));
    HostOffer lastOffer = null;
    for (HostOffer o: sortedOffers) {
      lastOffer = o;
    }
    assertEquals(OFFER_B, lastOffer);

    // filter OFFER_B out
    httpOfferSet = new HttpOfferSetImpl(offers,
        0,
        new URL("http://localhost:9090/v1/offerset"),
        0,
        1,
        true);
    sortedOffers = httpOfferSet.getOrdered(groupKey, resourceRequest);
    assertEquals(offers.size() - 1, Iterables.size(sortedOffers));
  }
}
