/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.infinispan.interceptors;

import org.infinispan.commands.AbstractVisitor;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.InvalidateCommand;
import org.infinispan.commands.write.InvalidateL1Command;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.PutMapCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.container.DataContainer;
import org.infinispan.container.OptimisticEntryFactory;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.interceptors.base.CommandInterceptor;
import org.infinispan.interceptors.locking.ClusteringDependentLogic;
import org.infinispan.remoting.transport.Transport;
import org.infinispan.util.ReversibleOrderedSet;
import org.infinispan.util.concurrent.TimeoutException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

/**
 * // TODO: Document this
 *
 * @author Mircea Markus
 * @since 5.1
 */
public class EntryWrappingInterceptor extends CommandInterceptor {

   private OptimisticEntryFactory entryFactory;
   private DataContainer dataContainer;
   private Transport transport;
   private ClusteringDependentLogic cll;
   private EntryWrappingVisitor entryWrappingVisitor;

   @Inject
   public void init(OptimisticEntryFactory entryFactory, DataContainer dataContainer, Transport transport,
                    ClusteringDependentLogic cll, EntryWrappingVisitor entryWrappingVisitor ) {
      this.entryFactory = entryFactory;
      this.dataContainer = dataContainer;
      this.transport = transport;
      this.cll = cll;
      this.entryWrappingVisitor = entryWrappingVisitor;
   }

   @Override
   public Object visitPrepareCommand(TxInvocationContext ctx, PrepareCommand command) throws Throwable {
      //just apply the changes, no need to acquire locks as this has already happened
      if (!ctx.isOriginLocal()) {
         for (WriteCommand c : command.getModifications()) {
            c.acceptVisitor(ctx, entryWrappingVisitor);
         }
      }
      return invokeNextAndCommitIf1Pc(ctx, command);
   }

   protected final Object invokeNextAndCommitIf1Pc(TxInvocationContext ctx, PrepareCommand command) throws Throwable {
      Object result = invokeNextInterceptor(ctx, command);
      if (command.isOnePhaseCommit()) {
         commitContextEntries(ctx);
      }
      return result;
   }

   private void commitContextEntries(TxInvocationContext ctx) {
      ReversibleOrderedSet<Map.Entry<Object, CacheEntry>> entries = ctx.getLookedUpEntries().entrySet();
      Iterator<Map.Entry<Object, CacheEntry>> it = entries.reverseIterator();
      if (trace) log.tracef("Number of entries in context: %s", entries.size());
      while (it.hasNext()) {
         Map.Entry<Object, CacheEntry> e = it.next();
         CacheEntry entry = e.getValue();
         if (entry != null && entry.isChanged()) {
            cll.commitEntry(entry, ctx.hasFlag(Flag.SKIP_OWNERSHIP_CHECK));
         } else {
            if (trace) log.tracef("Entry for key %s is null or not changed(%s), not calling commitUpdate", e.getKey(), entry);
         }
      }
   }


   @Override
   public final Object visitGetKeyValueCommand(InvocationContext ctx, GetKeyValueCommand command) throws Throwable {
      entryFactory.wrapEntryForReading(ctx, command.getKey());
      return super.visitGetKeyValueCommand(ctx, command);
   }

   @Override
   public final Object visitInvalidateCommand(InvocationContext ctx, InvalidateCommand command) throws Throwable {
      if (command.getKeys() != null) {
         for (Object key : command.getKeys())
            entryFactory.wrapEntryForWriting(ctx, key, false, true, false, false, false);
      }
      return invokeNextInterceptor(ctx, command);
   }

   @Override
   public final Object visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
      for (InternalCacheEntry entry : dataContainer.entrySet())
         entryFactory.wrapEntryForClear(ctx, entry.getKey());
      return invokeNextInterceptor(ctx, command);
   }

   @Override
   public Object visitInvalidateL1Command(InvocationContext ctx, InvalidateL1Command command) throws Throwable {
      Object keys [] = command.getKeys();
      if (keys != null && keys.length>=1) {
         ArrayList<Object> keysCopy = new ArrayList<Object>(Arrays.asList(keys));
         for (Object key : command.getKeys()) {
            ctx.setFlags(Flag.ZERO_LOCK_ACQUISITION_TIMEOUT);
            try {
               entryFactory.wrapEntryForWriting(ctx, key, false, true, false, false, false);
            } catch (TimeoutException te){
               log.unableToLockToInvalidate(key,transport.getAddress());
               keysCopy.remove(key);
               if(keysCopy.isEmpty())
                  return null;
            }
         }
         command.setKeys(keysCopy.toArray());
      }
      return invokeNextInterceptor(ctx, command);
   }

   @Override
   public final Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
      entryFactory.wrapEntryForPut(ctx, command.getKey(), !command.isPutIfAbsent());
      return invokeNextInterceptor(ctx, command);
   }

   @Override
   public final Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
      entryFactory.wrapEntryForRemove(ctx, command.getKey());
      return invokeNextInterceptor(ctx, command);
   }

   @Override
   public final Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
      entryFactory.wrapEntryForReplace(ctx, command.getKey());
      return invokeNextInterceptor(ctx, command);
   }

   @Override
   public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
      for (Object key : command.getMap().keySet()) {
         entryFactory.wrapEntryForPut(ctx, key, true);
      }
      return invokeNextInterceptor(ctx, command);
   }

   private final class EntryWrappingVisitor extends AbstractVisitor {

      @Override
      public Object visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
         boolean notWrapped = false;
         for (Object key : dataContainer.keySet()) {
            if (notWrapped(ctx, key)) {
               entryFactory.wrapEntryForClear(ctx, key);
               notWrapped = true;
            }
         }
         if (notWrapped)
            invokeNextInterceptor(ctx, command);
         return null;
      }

      @Override
      public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
         boolean notWrapped = false;
         for (Object key : command.getMap().keySet()) {
            if (cll.localNodeIsOwner(key)) { //todo - try to avoid this repeated call to localNodeIs. This also takes place in the locking interceptor
               if (notWrapped(ctx, key)) {
                  entryFactory.wrapEntryForPut(ctx, key, true);
                  notWrapped = true;
               }
            }
         }
         if (notWrapped)
            invokeNextInterceptor(ctx, command);
         return null;
      }

      @Override
      public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
         if (cll.localNodeIsOwner(command.getKey())) {
            if (notWrapped(ctx, command.getKey())) {
               entryFactory.wrapEntryForRemove(ctx, command.getKey());
               invokeNextInterceptor(ctx, command);
            }
         }
         return null;
      }

      @Override
      public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
         if (cll.localNodeIsOwner(command.getKey())) {
            if (notWrapped(ctx, command.getKey())) {
               entryFactory.wrapEntryForPut(ctx, command.getKey(), !command.isPutIfAbsent());
               invokeNextInterceptor(ctx, command);
            }
         }
         return null;
      }

      @Override
      public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
         if (cll.localNodeIsOwner(command.getKey())) {
            if (notWrapped(ctx, command.getKey())) {
               entryFactory.wrapEntryForReplace(ctx, command.getKey());
               invokeNextInterceptor(ctx, command);
            }
         }
         return null;
      }

      /**
       * this is needed for recovery, as prepare and commit is replayed and entries are not wrapped in the context.
       * //todo - revisit and check if it is needed
       */
      private boolean notWrapped(InvocationContext ctx, Object key) {
         return ctx.isOriginLocal() && !ctx.getLookedUpEntries().containsKey(key);
      }
   }

}
