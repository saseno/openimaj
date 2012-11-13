/**
 * Copyright (c) ${year}, The University of Southampton and the individual contributors.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *   * 	Redistributions of source code must retain the above copyright notice,
 * 	this list of conditions and the following disclaimer.
 *
 *   *	Redistributions in binary form must reproduce the above copyright notice,
 * 	this list of conditions and the following disclaimer in the documentation
 * 	and/or other materials provided with the distribution.
 *
 *   *	Neither the name of the University of Southampton nor the names of its
 * 	contributors may be used to endorse or promote products derived from this
 * 	software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.openimaj.rdf.storm.bolt;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.openimaj.rdf.storm.topology.bolt.StormReteBolt;
import org.openimaj.rdf.storm.topology.bolt.StormReteBolt.Component;
import org.openimaj.rdf.storm.utils.CircularPriorityWindow;

import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;

import com.hp.hpl.jena.graph.Graph;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.compose.MultiUnion;
import com.hp.hpl.jena.graph.compose.Polyadic;
import com.hp.hpl.jena.reasoner.rulesys.impl.RETERuleContext;

/**
 * Represents one input left of a join node. The queue points to
 * a sibling queue representing the other leg which should be joined
 * against.
 *
 * @author David Monks <dm11g08@ecs.soton.ac.uk>, based largely on the RETEQueue
 *         implementation by <a href="mailto:der@hplb.hpl.hp.com">Dave
 *         Reynolds</a>
 */
public class RETEStormQueue implements RETEStormSinkNode, RETEStormSourceNode {

	protected final static Logger logger = Logger.getLogger(RETEStormQueue.class);

	/** A time-prioritised and size limited sliding window of Tuples */
	private final CircularPriorityWindow<Tuple> window;

	/** A set of {@link Fields} which should match between the two inputs */
	protected final int[] matchIndices;

	/**
	 * A set of {@link Fields} which should be produced by joins between the two
	 * inputs
	 */
	protected final int[] outputIndices;

	/** The sibling queue which forms the other half of the join node */
	protected RETEStormQueue sibling;

	/** The node that results should be passed on to */
	protected RETEStormSinkNode continuation;

	/**
	 * Constructor. The window is not usable until it has been bound
	 * to a sibling and a continuation node.
	 *
	 * @param matchFields
	 *            Maps each field of the input tuple to the index of the
	 *            equivalent field in tuples from the other side of the join.
	 * @param outputFields
	 *            Maps each field of the output tuple to the index of the
	 *            equivalent field of the input tuple.
	 * @param size
	 * @param delay
	 * @param unit
	 */
	public RETEStormQueue(int[] matchFields,
			int[] outputFields,
			int size,
			long delay,
			TimeUnit unit) {
		this.matchIndices = matchFields;
		this.outputIndices = outputFields;
		this.window = new CircularPriorityWindow<Tuple>(size, delay, unit);
	}

	/**
	 * Constructor including sibling to bind to. The window is not usable until
	 * it has
	 * also been bound to a continuation node.
	 *
	 * @param matchFields
	 *            Maps each field of the input tuple to the index of the
	 *            equivalent field in tuples from the other side of the join.
	 * @param outputFields
	 *            Maps each field of the output tuple to the index of the
	 *            equivalent field of the input tuple.
	 * @param size
	 * @param delay
	 * @param unit
	 * @param sib
	 */
	public RETEStormQueue(int[] matchFields,
			int[] outputFields,
			int size,
			long delay,
			TimeUnit unit,
			RETEStormQueue sib) {
		this(matchFields, outputFields, size, delay, unit);
		this.setSibling(sib);
		sib.setSibling(this);
	}

	/**
	 * Constructor including sibling to bind to. The window is not usable until
	 * it has
	 * also been bound to a continuation node.
	 *
	 * @param matchFields
	 *            Maps each field of the input tuple to the index of the
	 *            equivalent field in tuples from the other side of the join.
	 * @param outputFields
	 *            Maps each field of the output tuple to the index of the
	 *            equivalent field of the input tuple.
	 * @param size
	 * @param delay
	 * @param unit
	 * @param sib
	 * @param sink
	 */
	public RETEStormQueue(int[] matchFields,
			int[] outputFields,
			int size,
			long delay,
			TimeUnit unit,
			RETEStormQueue sib,
			RETEStormSinkNode sink) {
		this(matchFields, outputFields, size, delay, unit, sib);
		this.setContinuation(sink);
	}

	/**
	 * Set the sibling for this node.
	 *
	 * @param sibling
	 */
	public void setSibling(RETEStormQueue sibling) {
		this.sibling = sibling;
	}

	/**
	 * Set the continuation node for this node (and any sibling)
	 */
	@Override
	public void setContinuation(RETEStormSinkNode continuation) {
		this.continuation = continuation;
		if (sibling != null)
			sibling.continuation = continuation;
	}

	/**
	 * Propagate a token to this node.
	 *
	 * @param env
	 *            a set of variable bindings for the rule being processed.
	 * @param isAdd
	 *            distinguishes between add and remove operations.
	 */
	public void fire(Tuple env, boolean isAdd, long timestamp) {
		// Cross match new token against the entries in the sibling queue
		List<Object> values = env.getValues();
		logger.debug("Checking new tuple values: " + values);
//		logger.debug("Comparing new tuple to " + sibling.window.size() + " other tuples");
		for (Iterator<Tuple> i = sibling.window.iterator(); i.hasNext();) {
			Tuple candidate = i.next();
			logger.debug("Comparing to tuple values: " + candidate.getValues());
			boolean matchOK = true;
			for (int j = 0; j < matchIndices.length; j++) {
				if (matchIndices[j] >= 0 && !((Node) values.get(j)).sameValueAs(candidate.getValue(matchIndices[j]))) {
//					logger.debug(String.format(
//							"this.matchIndices[j] == %d and value at this.value[%d] != that.value[%d]",
//							matchIndices[j], j, matchIndices[j]
//							));
					matchOK = false;
					break;
				}
			}
			if (matchOK) {
				logger.debug("Match Found! preparing for emit!");
				// Instantiate a new extended environment
				Values newVals = new Values();
				for (int j = 0; j < outputIndices.length; j++) {
					Object o;
					if (outputIndices[j] >= 0)
						o = env.getValue(outputIndices[j]);
					else
						o = candidate.getValue(sibling.outputIndices[j]);
					newVals.add(o);
				}

				for (Component c : Component.values()) {
					switch (c) {
					case isAdd:
						// insert this Tuple's value of isAdd to be passed onto subscribing Bolts.
						newVals.add(isAdd);
						break;
					case graph:
						Polyadic newG = new MultiUnion();
						newG.addGraph((Graph) env.getValueByField(StormReteBolt.Component.graph.toString()));
						newG.addGraph((Graph) candidate.getValueByField(StormReteBolt.Component.graph.toString()));
						// insert the new graph into the array of Values
						newVals.add(newG);
						break;
					case timestamp:
						newVals.add(timestamp);
						break;
					default:
						break;

					}
				}

				// Fire the successor processing
				continuation.fire(newVals, isAdd);
			}
		}

		if (isAdd)
			// Store the new token in this store
			window.offer(env);
		else
			// Remove any existing instances of the token from this store
			window.remove(env);

	}

	/**
	 * Clone this node in the network.
	 *
	 * @param netCopy
	 * @param context
	 *            the new context to which the network is being ported
	 * @return RETEStormNode
	 */
	@Override
	public RETEStormNode clone(Map<RETEStormNode, RETEStormNode> netCopy, RETERuleContext context) {
		RETEStormQueue clone = (RETEStormQueue) netCopy.get(this);
		if (clone == null) {
			clone = new RETEStormQueue(matchIndices, outputIndices, window.getCapacity(), window.getDelay(), TimeUnit.MILLISECONDS);
			netCopy.put(this, clone);
			clone.setSibling((RETEStormQueue) sibling.clone(netCopy, context));
			clone.setContinuation((RETEStormSinkNode) continuation.clone(netCopy, context));
			clone.window.addAll(window);
		}
		return clone;
	}

	@Override
	public void fire(Values output, boolean isAdd) {
		if (this == this.continuation) {
			System.out.println(output.toString());
			return;
		}
		this.continuation.fire(output, isAdd);
	}

	@Override
	public boolean isActive() {
		if (this == this.continuation) {
			return this.isActive();
		}
		return this.continuation.isActive();
	}

}