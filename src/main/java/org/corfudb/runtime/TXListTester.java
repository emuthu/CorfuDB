/**
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.corfudb.runtime;

import java.util.*;
import java.util.concurrent.CyclicBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.corfudb.runtime.collections.CorfuDBList;


class TXListTester<E, L extends CorfuDBList<E>> implements Runnable {

    private static Logger dbglog = LoggerFactory.getLogger(TXListTester.class);

    AbstractRuntime m_rt;
    List<L> m_v;
    CyclicBarrier m_startbarrier;
    CyclicBarrier m_stopbarrier;
    int m_nOps;
    int m_nKeys;
    int m_nId;
    ElemGenerator<E> m_generator;

    public
    TXListTester(
            int nId,
            CyclicBarrier startbarrier,
            CyclicBarrier stopbarrier,
            AbstractRuntime tcr,
            List<L> v,
            int nops,
            int nkeys,
            ElemGenerator<E> generator
            )
    {
        m_nId = nId;
        m_nOps = nops;
        m_nKeys = nkeys;
        m_v = v;
        m_rt = tcr;
        m_startbarrier = startbarrier;
        m_stopbarrier = stopbarrier;
        m_generator = generator;
    }

    private Pair<L, L> selectLists() {
        L src = null;
        L dst = null;
        ArrayList<L> lists = new ArrayList<L>();
        lists.addAll(m_v);
        while(lists.size() > 0 && (src == null || dst == null)) {
            int lidx = (int) (Math.random() * lists.size()-1);
            assert(lidx >= 0);
            L randlist = lists.remove(lidx);
            if(src == null && randlist.size() != 0)
                src = randlist;
            else if (src != null)
                dst = randlist;
        }
        if(src == null || dst == null)
            throw new RuntimeException("failed to select non-empty src and (potentially empty) dst lists!");
        return new Pair(src, dst);
    }

    private void
    moveRandomItem(
            L src,
            L dst
            ) {
        int startsize = src.sizeview();
        int viewsize = src.size();
        assert(viewsize > 0);
        int lidx = (int) (Math.random() * viewsize-1);
        E item = src.get(lidx);
        int aftergetsize = src.sizeview();
        src.remove(lidx);
        int afterremovesize = src.sizeview();
        dst.add(item);
        //if(startsize != aftergetsize || startsize != afterremovesize) {
            System.out.println("moveRandomItem sync-size:" + viewsize + ", viewsize[before,mid,after]=[" + startsize + "," + aftergetsize + "," + afterremovesize + "]");
        //}
    }

    private void
    moveRandomItemOrig(
            L src,
            L dst
        ) {
        int lidx = (int) (Math.random() * src.size()-1);
        E item = src.get(lidx);
        src.remove(lidx);
        dst.add(item);
    }

    public void
    populateListsCG() {

        // putting all this list creation in one coarse grain
        // transaction stresses the lower layers, which dont yet
        // support multi-entry writes. Technically, this is init code,
        // and is only using the tx layer because that's how to get the
        // data into the log. Keep this variation around in case we
        // ever need synchronization on this step. For now, prefer the
        // version below, which uses finer grain transactions.
        m_rt.BeginTX();
        for(int i=0; i<m_nKeys; i++) {
            int lidx = (int) (Math.random() * m_v.size());
            L randlist = m_v.get(lidx);
            randlist.add(m_generator.randElem(i));
            int size = randlist.size();
        }
        m_rt.EndTX();
    }

    public void
    populateLists() {

        for(int i=0; i<m_nKeys; i++) {
            int lidx = (int) (Math.random() * m_v.size());
            L randlist = m_v.get(lidx);
            m_rt.BeginTX();
            randlist.add(m_generator.randElem(i));
            int size = randlist.size();
            m_rt.EndTX();
        }
    }

    public void run()
    {
        int numcommits = 0;
        int naborts = 0;
        if(m_nId == 0)
            populateLists();
        System.out.println("starting tx list tester thread " + m_nId);
        try {
            m_startbarrier.await();
        } catch(Exception bbe) {
            throw new RuntimeException(bbe);
        }
        for(int i=0;i<m_nOps;i++)
        {
            long curtime = System.currentTimeMillis();
            dbglog.debug("Tx starting...");
            m_rt.BeginTX();
            Pair<L, L> pair = selectLists();
            moveRandomItem(pair.first, pair.second);
            if(m_rt.EndTX()) numcommits++;
            else naborts++;
            dbglog.debug("Tx took {}", (System.currentTimeMillis()-curtime));
        }
        try {
            m_stopbarrier.await();
        } catch(Exception bbe) {
            throw new RuntimeException(bbe);
        }
        System.out.println("Tester thread is done: " + numcommits + " commits out of " + m_nOps);
    }

}
