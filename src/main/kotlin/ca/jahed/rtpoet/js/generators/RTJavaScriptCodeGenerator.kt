package ca.jahed.rtpoet.js.generators

import ca.jahed.rtpoet.rtmodel.*
import ca.jahed.rtpoet.rtmodel.rts.RTSystemSignal
import ca.jahed.rtpoet.rtmodel.rts.classes.RTSystemClass
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTSystemProtocol
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTTimingProtocol
import ca.jahed.rtpoet.rtmodel.sm.*
import ca.jahed.rtpoet.rtmodel.values.*
import ca.jahed.rtpoet.utils.RTQualifiedNameHelper
import ca.jahed.rtpoet.js.utils.ZipUtils
import ca.jahed.rtpoet.utils.composition.RTConnection
import ca.jahed.rtpoet.utils.composition.RTModelFlattener
import ca.jahed.rtpoet.utils.composition.RTSlot
import java.io.File
import java.io.FileWriter
import kotlin.math.min

class RTJavaScriptCodeGenerator private constructor(
    private val model: RTModel,
    var currentDir: File,
    var debug: Boolean = false
) {

    private val slots: List<RTSlot> = RTModelFlattener.flatten(model)
    private val portConnectionsMap = mutableMapOf<Pair<RTCapsulePart, RTPort>, MutableSet<RTConnection>>()

    private val qualifiedNames = RTQualifiedNameHelper(model).getQualifiedNames()
    private val outgoingTransitions = mutableMapOf<RTGenericState, MutableList<RTTransition>>()

    private var expressPort = 3000
    private var smInspectorPort = 8888

    companion object {
        @JvmStatic
        fun generate(model: RTModel, outputDir: String, debug: Boolean = false): Boolean {
            return generate(model, File(outputDir), debug)
        }

        @JvmStatic
        fun generate(model: RTModel, outputDir: File, debug: Boolean = false): Boolean {
            return RTJavaScriptCodeGenerator(model, outputDir, debug).doGenerate()
        }
    }

    private fun doGenerate(): Boolean {
        if(slots.isEmpty()) return false
        if(debug) ZipUtils.extractZipResource("xstate-inspector.zip", currentDir)

        slots.forEach { slot ->
            slot.connections.forEach { srcPort, connections ->
                connections.filter { it.wired }.forEach { connection ->
                    portConnectionsMap.computeIfAbsent(Pair(slot.part, srcPort)) { mutableSetOf() }.add(connection)
                }
            }
        }

        qualifiedNames.keys.filterIsInstance<RTGenericState>().forEach { outgoingTransitions[it] = mutableListOf() }
        qualifiedNames.keys.filterIsInstance<RTTransition>().forEach { outgoingTransitions[it.source]!!.add(it) }

        generateRTS()
        generateModel(model)
        generateRoutingTable(slots)
        return true
    }


    private fun enterDir(name: String) {
        currentDir = File(currentDir, name)
        currentDir.mkdirs()
    }

    private fun leaveDir() {
        currentDir = currentDir.parentFile
    }

    private fun writeFile(name: String, content: String) {
        val writer = FileWriter(File(currentDir, name))
        writer.write(formatOutput(content))
        writer.close()
    }

    private fun formatOutput(code: String): String {
        var output = ""
        var indentation = 0
        val bracketStack = mutableListOf<Char>()

        code.split("\n").forEach {
            val line = it.trim()
            if (line.isNotEmpty()) {
                line.toCharArray().filter { c -> c in "{[()]}"}.forEach { c ->
                    if(c in "{[(") bracketStack.add(c)
                    else if(c in ")]}") bracketStack.removeLast()
                }

                output += "\t".repeat(min(bracketStack.size, indentation)) + line + "\n"
                indentation = bracketStack.size
            }
        }

        return output.trim()
    }

    private fun String.write(file: String) {
        writeFile(file, this)
    }

    private fun String.toFunctionName(): String {
        return this.replace(".", "_").toLowerCase()
    }

    private fun String.toStateId(): String {
        return this.replace(".", "_").toLowerCase()
    }

    private fun generateModel(model: RTModel) {
        """
            {
            	"name": "${model.name}",
            	"main": "index.js",
            	"engines": {
                    "node": ">= 4.3.2"
                },
              	"dependencies": {
                	"comedy": "^2.1.5",
                	"xstate": "^4.19.1",
                    ${if(debug) """
                    "@xstate/inspect": "^0.4.1",
                    "express": "^4.17.1",
                    "ws": "^7.5.3",
                    "open": "^8.2.1",
                    """.trimIndent() else ""}
                    "rtpoet-rts": "file:./rtpoet-rts"
              	},
                "scripts": {
                    "preinstall": "npm --prefix ./rtpoet-rts install",
                    "start": "node index.js",
            		"node": "./node_modules/.bin/comedy-node"
                }
            }
        """.trimIndent().write("package.json")

        """
            {
                ${slots.joinToString(",\n") { """
                    "${it.name}" : {
                        "mode": ${if(debug) """ "in-memory", """ else """ "forked" """}
                        ${if(debug) """ "debug": false """ else ""}
                    }
                """.trimIndent() }}
            }
        """.trimIndent().write("actors.json")

        """
            const actors = require('comedy');
            const Port = require('rtpoet-rts/Port');
            const ${slots.first().part.capsule.name} = require('./${slots.first().part.capsule.name}');
            ${if(debug) """
            const open = require('open');
            const express = require('express');
            const app = express();
            """.trimIndent() else ""}
             
            actors()
                .rootActor()
                .then(rootActor => rootActor.createChild('/${slots.first().part.capsule.name}', {
                    name : '${slots.first().name}',
                    customParameters: {
                        name: '${slots.first().name}',
                        index: 0,
                        fqn: '${slots.first().name}',
                        data: {}
                    }
                }))
                .then(childActor => {
                    childActor.send('run');
                });
                
           ${if(debug) """
            app.use(express.static(__dirname + '/inspector'));
            app.listen(${expressPort});
            
            open("http://localhost:${expressPort}/inspector.html?server=localhost:${smInspectorPort}");
            console.log("Inspector: http://localhost:${expressPort}/inspector.html?server=localhost:${smInspectorPort}");
            """.trimIndent() else ""}
        """.trimIndent().write("index.js")

        generatePackage(model)
    }

    private fun generateRoutingTable(slots: List<RTSlot>) {
        """
            const routes = {
                ${slots.joinToString(",\n") { slot -> """
                    '${slot.name}': {
                        ${slot.connections.keys.joinToString(",\n") { srcPort -> """
                            '${srcPort.name}': [
                                ${slot.connections[srcPort]!!.filter { it.wired }.joinToString(",\n") { connection -> """
                                {
                                    slot: '${connection.slot.name}',
                                    part: '${connection.slot.part.name}',
                                    index: ${connection.slot.index},
                                    port: '${connection.port.name}'
                                }
                                """.trimIndent() }}
                            ]
                        """.trimIndent() }}
                    }
                """.trimIndent() }}
            }
            
            module.exports = routes;
        """.trimIndent().write("routes.js")
    }

    private fun generatePackage(pkg: RTPackage) {
        if(pkg !is RTModel) enterDir(pkg.name)
        pkg.packages.forEach { generatePackage(it) }
        pkg.protocols.forEach { generateProtocol(it) }
        pkg.classes.forEach { generateClass(it) }
        pkg.enumerations.forEach { generateEnumeration(it) }
        pkg.capsules.forEach { generateCapsule(it) }
        if(pkg !is RTModel) leaveDir()
    }

    private fun generateCapsule(capsule: RTCapsule) {
        """
            const Part = require('./rtpoet-rts/Part');
            const Port = require('./rtpoet-rts/Port');
            const Capsule = require('./rtpoet-rts/Capsule');
            const FrameService = require('./rtpoet-rts/FrameService');
            const routes = require('./routes');
            
            ${if(debug && slots.first().part.capsule.name == capsule.name) """
            const { inspect } = require("@xstate/inspect/lib/server");
            const WebSocket = require("ws");            
            """.trimIndent() else ""}

            ${capsule.attributes.map{ it.type }.filterIsInstance<RTSystemClass>().toSet().joinToString("\n") { """
                const ${it.name} = require('./rtpoet-rts/${it.name}')
            """.trimIndent() }}
            
            ${capsule.ports.map{ it.protocol }.filterIsInstance<RTSystemProtocol>().toSet().joinToString("\n") { """
                const ${it.name} = require('./rtpoet-rts/${it.name}')
                ${if(it === RTTimingProtocol) "const Timespec = require('./rtpoet-rts/Timespec')" else ""}
            """.trimIndent() }}
            
            ${capsule.attributes.map{ it.type }.filter { (it is RTClass || it is RTEnumeration) 
                && it !is RTSystemClass }.toSet().joinToString("\n") { """
                const ${it.name} = require('./${it.name}')
            """.trimIndent() }}
            
            ${capsule.ports.map{ it.protocol }.filter { it !is RTSystemProtocol }.toSet().joinToString("\n") { """
                const ${it.name} = require('./${it.name}')
            """.trimIndent() }}
            
            ${capsule.parts.map{ it.type }.toSet().joinToString("\n") { """
                const ${it.name} = require('./${it.name}')
            """.trimIndent() }}

            module.exports = class ${capsule.name} extends Capsule {
            	static modulePath() {
            		return '/'.concat('${capsule.name}');
            	}

            	constructor() {
            		super();
                    ${if(debug && slots.first().part.capsule.name == capsule.name) """
                    inspect({server: new WebSocket.Server({port: $smInspectorPort})})
                    """.trimIndent() else ""}
                    
            		this.context = {
                        ${capsule.attributes.joinToString(",\n") { attr -> """
                            ${attr.name}: ${when {
                                attr.value != null -> generateValue(attr.value!!)
                                attr.replication > 1 -> "[]"
                                attr is RTClass -> "new ${attr.name}"
                                else -> "null"
                            }}
                        """.trimIndent() }}
                    };
            		
                    ${capsule.parts.joinToString("\n") { part -> """
                        this.${part.name} = new Part('${part.name}', ${part.capsule.name}, ${part.optional}, ${part.plugin}, ${part.replication}, {
                            ${part.capsule.ports.filter { p -> !p.isInternal() }.joinToString(",\n") { port -> """
                                '${port.name}': {
                                    name: '${port.name}',
                                    protocol: '${port.protocol.name}',
                                    isConjugated: ${port.conjugated},
                                    isWired: ${port.wired},
                                    isPublish: ${port.publish},
                                    isRelay: ${port.isRelay()},
                                    isNotification: ${port.notification},
                                    isAutomaticRegistration: ${port.registrationType !== RTPort.RegistrationType.APPLICATION},
                                    registrationOverride: '${port.registrationOverride}',
                                    replication: ${port.replication}
                                }
                            """.trimIndent() }}
                        });  
                    """.trimIndent() }}

                    this.internalPorts = {
                        ${capsule.ports.filter { p -> p.isInternal() }.joinToString(",\n") { port -> """
                            '${port.name}': new Port (
                                '${port.name}',
                                '${port.protocol.name}',
                                ${port.conjugated},
                                ${port.service},
                                ${port.wired},
                                ${port.publish},
                                ${port.isRelay()},
                                ${port.notification},
                                ${port.registrationType !== RTPort.RegistrationType.APPLICATION},
                                '${port.registrationOverride}', 
                                ${port.replication},
                                this
                            )
                        """.trimIndent() }}
                    }
            
                    this.borderPorts = {
                        ${capsule.ports.filter { p -> !p.isInternal() }.joinToString(",\n") { port -> """
                                '${port.name}': new Port (
                                '${port.name}',
                                '${port.protocol.name}',
                                ${port.conjugated},
                                ${port.service},
                                ${port.wired},
                                ${port.publish},
                                ${port.isRelay()},
                                ${port.notification},
                                ${port.registrationType !== RTPort.RegistrationType.APPLICATION},
                                '${port.registrationOverride}', 
                                ${port.replication},
                                this
                            )
                        """.trimIndent() }}
                    }       

                    ${capsule.ports.joinToString("\n") { port -> """
                        this.${port.name} = new ${port.protocol.name}.${if(port.conjugated) "conjugated" else "base"} (this.${if(!port.isInternal()) "borderPorts" else "internalPorts"}['${port.name}']);
                    """.trimIndent() }}

                    this.parts = {
                        ${capsule.parts.joinToString(",\n") { part -> """
                            '${part.name}': this.${part.name}
                        """.trimIndent() }}
                    };	
                    
                    ${if(capsule.stateMachine != null) generateStateMachine(capsule.stateMachine!!) else ""}
            	}
            
            	initialize(selfActor) {
            		this.actor = selfActor;
            		this.actorParams = selfActor.getCustomParameters();
            		this.name = this.actorParams.name;
            		this.index = this.actorParams.index;
                    this.fqn = this.actorParams.fqn;
                   
                    this.routingTable = {
                        ${capsule.parts.joinToString(",\n") { part -> """
                            '${part.name}': [
                                ${(0 until part.replication).joinToString(",\n") { index -> """
                                    routes[this.fqn + ".${part.name}[${index}]"]
                                """.trimIndent() }}
                            ]
                        """.trimIndent() }}
                    };
                    
                    this.routingTable[this.name] = routes[this.fqn];
                    
            		let promises = [
                        ${capsule.parts.filter { p -> !p.optional && !p.plugin }.joinToString(",\n") { part -> 
                            (0 until part.replication).joinToString(",\n") { idx -> """
                                selfActor.createChild(${part.capsule.name}.modulePath(), {
                                    name: this.fqn + '.${part.name}[$idx]',
                                    customParameters: {
                                        name: '${part.name}',
                                        index: $idx,
                                        fqn: this.fqn + '.${part.name}[$idx]'
                                    } 
                                }).then(childActor => {
                                    this.parts['${part.name}'].add(childActor, $idx)
                                })
                            """.trimIndent() } 
                        }}
            		];

            		return Promise.all(promises).then(actors => {
            			this.wireParts();
            		});
            	}

                ${capsule.operations.joinToString("\n") { generateOperation(it) }}   
                     
                ${if(capsule.stateMachine != null)
                    qualifiedNames.filterKeys { it is RTAction }
                        .filterValues { it.startsWith(qualifiedNames[capsule.stateMachine!!]!!) }.keys
                        .joinToString("\n") {
                            """
                                ${qualifiedNames[it]!!.toFunctionName()}(context, msg) {
                                    let rtData = msg.data;
                                    ${(it as RTAction).body}
                                }
                            """.trimIndent() }
                else ""}
            }
        """.trimIndent().write("${capsule.name}.js")
    }

    private fun getConnectors(capsule: RTCapsule, port: RTPort, part: RTCapsulePart? = null): List<RTConnector> {
        return capsule.connectors.filter {
            (it.end1.port == port && it.end1.part == part) || (it.end2.port == port && it.end2.part == part)
        }
    }

    private fun getOtherEnd(connector: RTConnector, port: RTPort, part: RTCapsulePart? = null): RTConnectorEnd {
        return if(connector.end1.part == part && connector.end1.port == port) connector.end2 else connector.end1
    }

    private fun generateClass(klass: RTClass) {
        """
            ${klass.attributes.map{ it.type }.filterIsInstance<RTSystemClass>().toSet().joinToString("\n") { """
                const ${it.name} = require('./rtpoet-rts/${it.name}')
            """.trimIndent() }}
            
            ${klass.attributes.map{ it.type }.filter { (it is RTClass || it is RTEnumeration)
                && it !is RTSystemClass }.toSet().joinToString("\n") { """
                const ${it.name} = require('./${it.name}')
            """.trimIndent() }}
            
            module.exports = class ${klass.name} {
                constructor() {
                    ${klass.attributes.joinToString(",\n") { attr -> """
                        this.${attr.name} = ${when {
                        attr.value != null -> generateValue(attr.value!!)
                        attr.replication > 1 -> "[]"
                        attr is RTClass -> "new ${attr.name}"
                        else -> "null"
                    }}
                    """.trimIndent() }}
                }
            
                ${klass.operations.joinToString("\n") { generateOperation(it) }}
            }
        """.trimIndent().write("${klass.name}.js")
    }

    private fun generateProtocol(protocol: RTProtocol) {
        val inputs = protocol.inputs().filter { it !is RTSystemSignal && it !== protocol.anySignal }
        val outputs = protocol.outputs().filter { it !is RTSystemSignal && it !== protocol.anySignal }

        """
            const Signal = require('rtpoet-rts/Signal');

            module.exports = {
            	base: function(port) {
            		this.port = port;
            	
                    this.recall = function() {
                        port.recall();
                    }
        
                    this.recallAll = function() {
                        port.recallAll();
                    }
                                
            		this.in = function() {
                        ${inputs.joinToString("\n") { generateInSignal(it) }}
            		};
            		
                    ${outputs.joinToString("\n") { generateOutSignal(it) }}
            	},

            	conjugated: function(port) {
            		this.port=port;
            	
                    this.recall = function() {
                        port.recall();
                    }
        
                    this.recallAll = function() {
                        port.recallAll();
                    }
                                    
            		this.in = function() {
                        ${outputs.joinToString("\n") { generateInSignal(it) }}
            		};
            		
                    ${inputs.joinToString("\n") { generateOutSignal(it) }}
            	}
            }
        """.trimIndent().write("${protocol.name}.js")
    }

    private fun generateEnumeration(enumeration: RTEnumeration) {
        """
            module.exports = Object.freeze({
                ${enumeration.literals.joinToString(",\n") { it }}
            });
        """.trimIndent().write("${enumeration.name}.js")
    }

    private fun generateInSignal(signal: RTSignal): String {
        return """
            this.${signal.name} = function(${signal.parameters.joinToString(", ") { it.name }}) {
                ${signal.parameters.joinToString("\n") { param -> """
                    this.${param.name} = ${param.name}
                """.trimIndent() }}
            }
        """.trimIndent()
    }

    private fun generateOutSignal(signal: RTSignal): String {
        return """
            this.${signal.name} = function(${signal.parameters.joinToString(", ") { it.name }}) {
                return new Signal(port, '${signal.name}', {
                    ${signal.parameters.joinToString(",\n") { param -> """
                        '${param.name}': ${param.name}
                    """.trimIndent() }}
                })
            }
        """.trimIndent()
    }

    private fun generateValue(value: RTValue): String {
        return when(value) {
            is RTLiteralString -> "'${value.value}'"
            is RTLiteralBoolean -> value.value.toString()
            is RTLiteralInteger -> value.value.toString()
            is RTLiteralReal -> value.value.toString()
            is RTExpression -> value.value.toString()
            else -> "null"
        }
    }

    private fun generateAction(action: RTAction): String {
        return action.body
    }

    private fun generateOperation(operation: RTOperation): String {
        return """
            ${operation.name}(${operation.parameters.joinToString(", ") { it.name }}) {
                ${if(operation.action != null) generateAction(operation.action!!) else ""}
            }
        """.trimIndent()
    }

    private fun generateStateMachine(statemachine: RTStateMachine): String {
        return """
            this.stateMachine = {
            	context: this.context,
                ${generateCompositeState(statemachine.states())}
            };
        """.trimIndent()
    }

    private fun generateCompositeState(states: List<RTGenericState>): String {
        if(states.isEmpty()) return ""

        val initialState = states.firstOrNull {
            it is RTPseudoState && it.kind == RTPseudoState.Kind.INITIAL
        }

        return """
            ${if(initialState != null) "initial: '${initialState.name}'," else ""}
            	states: {
                    ${if(initialState != null) """
                        '${initialState.name}': {
                            id: '${qualifiedNames[initialState]!!.toStateId()}',
                            ${if(outgoingTransitions[initialState]!!.isNotEmpty()) """
                                entry: (context, msg) => {
                                    this.interpreter.send({type: '__init', data: this.actorParams.initData});
                                },
                                on: {
                                    '__init': ${generateTransitionTargets(outgoingTransitions[initialState]!!.first())}
                                }
                            """.trimIndent() else ""}
                        },
                    """.trimIndent() else ""}
                    ${states.filterIsInstance<RTState>().joinToString("\n") { state -> """
                        '${state.name}': {
                            id: '${qualifiedNames[state]!!.toStateId()}',
                            ${if(state is RTCompositeState) generateCompositeState(state.states()) else ""}
                            ${if(state.entryAction != null) "entry: (context, msg) => this.${qualifiedNames[state.entryAction!!]!!.toFunctionName()}(context, msg)," else ""}
                            ${if(state.exitAction != null) "exit: (context, msg) => this.${qualifiedNames[state.exitAction!!]!!.toFunctionName()}(context, msg)," else ""}
                            ${if(outgoingTransitions[state]!!.isNotEmpty()) generateTransitions(state) else ""}
                        },
                    """.trimIndent() }}
                    ${states.filter {it is RTPseudoState && it.kind != RTPseudoState.Kind.INITIAL && it.kind != RTPseudoState.Kind.HISTORY}.joinToString("\n") { state -> """
                        '${(state as RTPseudoState).kind.name}_${state.name}': {
                            id: '${qualifiedNames[state]!!.toStateId()}',
                            ${if(outgoingTransitions[state]!!.isNotEmpty()) generateTransitions(state) else ""}
                        },
                    """.trimIndent() }}
                },
        """.trimIndent()
    }

    private fun generateTransitions(state: RTGenericState): String {
        val transitions = mutableListOf<RTTransition>()
        transitions.addAll(outgoingTransitions[state]!!.filter { it.guard != null })
        transitions.addAll(outgoingTransitions[state]!!.filter { it.guard == null })
        if(transitions.isEmpty()) return ""

        return """
            ${if(state is RTPseudoState) "always: [ " else "on: {"}
                ${transitions.joinToString(",\n") { """
                    ${if(state is RTPseudoState) generateTransitionTargets(it) else generateTransition(it)}
                """.trimIndent() }}
            ${if(state is RTPseudoState) "]" else "}"}
        """.trimIndent()
    }

    private fun generateTransition(transition: RTTransition): String {
        return transition.triggers.joinToString(",\n") { trigger -> """
            ${trigger.ports.joinToString(",\n") { port -> """
                '${port.name}::${trigger.signal.name}': [
                    ${generateTransitionTargets(transition)}
                ]
            """.trimIndent() }}
        """.trimIndent() }
    }

    private fun generateTransitionTargets(
        transition: RTTransition,
        chain: MutableList<RTTransition> = mutableListOf(),
    ): String {
        chain.add(transition)

        return """
            {
                target: '#${qualifiedNames[transition.target]!!.toStateId()}',
                ${generateGuardChain(chain)}
                ${generateActionChain(chain)}
            }
        """.trimIndent()
    }

    private fun generateGuardChain(chain: MutableList<RTTransition>): String {
        val guards = chain.filter { it.guard != null }.map { it.guard }
        if(guards.isEmpty()) return ""

        return """
            cond: (context, msg) => {
                return ${guards.joinToString("&&\n") { guard -> """
                    this.${qualifiedNames[guard!!]!!.toFunctionName()}(context, msg)
                """.trimIndent() }};
            },
        """.trimIndent()
    }

    private fun generateActionChain(chain: MutableList<RTTransition>): String {
        val actions = chain.filter { it.action != null }.map { it.action }
        if(actions.isEmpty()) return ""

        return """
            actions: (context, msg) => {
                ${actions.joinToString("\n") { action -> """
                    this.${qualifiedNames[action!!]!!.toFunctionName()}(context, msg);
                """.trimIndent() }}
            },
        """.trimIndent()
    }

    private fun generateRTS() {
        enterDir("rtpoet-rts")

        """
            {
                "name": "rtpoet-rts",
                "version": "1.0.0",
                "description": "RTPoet Runtime System",
                "author": "Karim Jahed",
                "license": "EPL",
                "engines": {
                    "node": ">= 4.3.2"
                },
                "dependencies": {
                    "nanotimer": "^0.3.15"
                }
            }
        """.trimIndent().write("package.json")

        generatePortClass().write("Port.js")
        generateSignalClass().write("Signal.js")
        generateCapsuleClass().write("Capsule.js")
        generatePartClass().write("Part.js")
        generateLogProtocol().write("Log.js")
        generateTimingProtocol().write("Timing.js")
        generateTimespec().write("Timespec.js")
        generateFrameProtocol().write("Frame.js")
        generateFrameService().write("FrameService.js")

        leaveDir()
    }

    private fun generatePortClass(): String {
        return """
            const Signal = require('./Signal');
    
            module.exports = class Port {
                constructor(name, protocol, isConjugated = false, isService = true, isWired = true, isPublish = false,
                    isRelay = false, isNotification = false, isAutomaticRegistration = true, registrationOverride = '', replication = 1, capsule = null) {
                    this.name = name;
                    this.protocol = protocol;
                    this.isConjugated = isConjugated;
                    this.isWired = isWired;
                    this.isService = isService;
                    this.isPublish = isPublish;
                    this.isRelay = isRelay;
                    this.isNotification = isNotification;
                    this.isAutomaticRegistration = isAutomaticRegistration;
                    this.registrationOverride = registrationOverride;
                    this.replication = replication;
                    this.capsule = capsule;
                    this.deferredQueue = [];
                    this.farEnds = Array(this.replication).fill(0);
                }
             
                connect(...farends) {
                    for(let i=0; i<farends.length; i++) {
    
                        if(this.getIndex(farends[i]) != -1)
                            return false;
    
                        let index = this.getNextAvailableIndex();
                        if(index == -1)
                            return false;
    
                        this.farEnds[index] = farends[i];
    
                        if(this.isNotification) {
                            this.sendToSelf(Signal.rtBound());
                        }
                    }
    
                    return true;
                }
    
                getIndex(farend) {
                    for (let i = 0; i < this.replication; i++)
                        if(this.farEnds[i]
                            && this.farEnds[i].part == farend.part
                            && this.farEnds[i].index == farend.index
                            && this.farEnds[i].port == farend.port)
                            return i;
    
                    return -1;
                }
    
                disconnect(farend) {
                    let index = this.getIndex(farend);
                    if(index != -1) {
                        this.farEnds[index] = 0;
    
                        if(this.isNotification) {
                            this.sendToSelf(Signal.rtUnbound());
                        }
                    }
                }
    
                getNextAvailableIndex() {
                    for(let i=0; i<this.replication; i++)
                        if(!this.farEnds[i])
                            return i;
                    return -1;
                }
    
                send(signal) {
                    for (let i = 0; i < this.replication; i++) {
                        if(this.farEnds[i])
                            this.sendAt(signal, i);
                    }
                }
    
                sendAt(signal, index) {
                    if(index >= this.replication
                        || !this.farEnds[index]) {
                        return false;
                    }
    
                    this.capsule.deliver(signal.toMessage(this.farEnds[index], this.capsule.index));
                }
    
                sendToSelf(signal) {
                    this.capsule.deliver(signal.toMessage({
                        slot: this.capsule.fqn,
                        part: this.capsule.name,
                        index: this.capsule.index,
                        port: this.name
                    }, this.capsule.index));
                }
    
                defer(message) {
                    this.deferredQueue.push(message);
                }
    
                recall() {
                    if(this.deferredQueue.length > 0)
                        this.capsule.deliver(this.deferredQueue.pop());
                }
    
                recallAll() {
                    while(this.deferredQueue.length > 0)
                        this.capsule.deliver(this.deferredQueue.pop());
                }
            }
        """.trimIndent()
    }

    private fun generateSignalClass(): String {
        return """
            module.exports = class Signal {
            	static rtBound(port) {
            			return new Signal(port, 'rtBound');
            	}
            	
            	static rtUnbound(port) {
            			return new Signal(port, 'rtUnbound');
            	}

            	static fromMessage(msg, port) {
            		return new Signal(port, msg.signal, msg.data);
            	}

            	constructor(port, type, data) {
            		this.port = port;
            		this.type = type;
            		this.data = data;
            	}

            	send() {
            		return this.port.send(this);
            	}

            	sendAt(index) {
            		return this.port.sendAt(this, index);
            	}

            	toMessage(farend, srcPortIdx) {
            		let self = this;

            		return {
            			srcPortIdx: srcPortIdx,
                        destSlot: farend.slot,
            			destPart: farend.part,
            			destPartIdx: farend.index,
            			destPort: farend.port,
            			signal: this.type,
            			data: this.data
            		}
            	}
            }
        """.trimIndent()
    }

    private fun generateCapsuleClass(): String {
        return """
            const Signal = require('./Signal');
            const { Machine, State, interpret } = require('xstate');

            module.exports = class Capsule {                
            	constructor() {                      
            		this.name = '';
            		this.index = -1;
                    this.fqn = '';
            		this.parts = {};
            		this.borderPorts = [];
            		this.internalPorts = [];
            		this.routingTable = [];
            		this.stateMachine = null;
            	}

            	wireParts() {
            		for(let partName in this.parts)
            			for(let slotIdx = 0; slotIdx<this.parts[partName].replication; slotIdx++)
            				if(this.parts[partName].slots[slotIdx])
            					this.wireSlot(partName, slotIdx);
            	}

            	wireSlot(partName, slotIdx) {
            		for(let portName in this.routingTable[partName][slotIdx]) {
                        for(let farEndIdx = 0; farEndIdx<this.routingTable[partName][slotIdx][portName].length; farEndIdx++) {
                                this.parts[partName].slots[slotIdx].send('wirePort', portName, this.routingTable[partName][slotIdx][portName][farEndIdx]);
                        }
            		}

            		for(let portName in this.routingTable[this.name]) {
            			for(let farEndIdx = 0; farEndIdx<this.routingTable[this.name][portName].length; farEndIdx++) {
            				let destPart = this.routingTable[this.name][portName][farEndIdx].part;
            				let destIndex = this.routingTable[this.name][portName][farEndIdx].index;
            				if(destPart == partName && destIndex == slotIdx)
            					this.wirePort(portName, this.routingTable[this.name][portName][farEndIdx])
            			}
            		}
            	}

            	unwireSlot(partName, slotIdx) {
            		for(let portName in this.routingTable[partName][slotIdx]) {
            			for(let farEndIdx = 0; farEndIdx<this.routingTable[partName][slotIdx][portName].length; farEndIdx++) {
            					this.parts[partName].slots[slotIdx].send('unwirePort', portName, this.routingTable[partName][slotIdx][portName][farEndIdx]);
            			}
            		}

            		for(let portName in this.routingTable[this.name][0]) {
            			for(let farEndIdx = 0; farEndIdx<this.routingTable[this.name][0][portName].length; farEndIdx++) {
            				let destPart = this.routingTable[this.name][0][portName][farEndIdx].part;
            				let destIndex = this.routingTable[this.name][0][portName][farEndIdx].index;
            				if(destPart == partName && destIndex == slotIdx)
            					this.unwirePort(portName, this.routingTable[this.name][0][portName][farEndIdx])
            			}
            		}
            	}


            	wireSap(part, port, candidates) {
            		let slotIndex = 0;
            		let portIndex = 0;

            		for(let i=0; i<candidates.length; i++) {
            				let candidatePart = this.parts[candidates[i].part];
            				let candidatePort = candidatePart.borderPorts[candidates[i].port];

            				for(let j=0; j<candidatePart.replication; j++) {
            						if(!candidatePart.slots[j])
            							continue;

            						let availableFarEnds = candidatePort.replication -
            							this.routingTable[candidatePart.name][j][candidatePort.name].length;

            						while(availableFarEnds > 0) {
            							if(slotIndex >= part.replication)
            								return;

            							if(!part.slots[slotIndex]) {
            								slotIndex++;
            								continue;
            							}

            							if(portIndex >= port.replication) {
            								slotIndex++;
            								portIndex = 0;
            								continue;
            							}

            							this.routingTable[candidatePart.name][j][candidatePort.name].push({
            								part: part.name,
            								index: slotIndex,
            								port: port.name
            							});

            							this.routingTable[part.name][slotIndex][port.name].push({
            								part: candidatePart.name,
            								index: j,
            								port: candidatePort.name
            							});

            							part.slots[slotIndex].send('wirePort', port.name, {
            								part: candidatePart.name,
            								index: j,
            								port: candidatePort.name
            							});

            							candidatePart.slots[j].send('wirePort', candidatePort.name, {
            								part: part.name,
            								index: slotIndex,
            								port: port.name
            							});

            							portIndex++;
            							availableFarEnds--;
            						}
            				}
            		}
            	}

            	getCandidateSppPorts(sap) {
            		let primeCandidates = [];	// matching registration override
            		let otherCandidates = [];

            		for(let partName in this.parts) {
            	    let part = this.parts[partName];

            			for(let portName in part.borderPorts) {
            				let port = part.borderPorts[portName];
            				if(!port.isWired
            					&& port.isPublish
            					&& port.isAutomaticRegistration
            					&& port.isConjugated == !sap.isConjugated
            					&& port.protocol == sap.protocol) {
            						if(port.registrationOverride == sap.registrationOverride)
            							primeCandidates.push({
            								part: partName,
            							  port: portName
            							});
            						else
            							otherCandidates.push({
            								part: partName,
            								port: portName
            							});
            				}
            			}
            		}

            		return primeCandidates.concat(otherCandidates);	//prime candidates first
            	}

            	unwirePort(port, farEnd) {
            		if(this.borderPorts[port])
            			return this.borderPorts[port].disconnect(farEnd);
            		else if(this.internalPorts[port])
            			return this.internalPorts[port].disconnect(farEnd);
            		return false;
            	}

            	wirePort(port, farEnd) {
            		if(this.borderPorts[port])
            			return this.borderPorts[port].connect(farEnd);
            		else if(this.internalPorts[port])
            			return this.internalPorts[port].connect(farEnd);
            		return false;
            	}

            	onImport(partName, partIndex) {
            		this.name = partName;
            		this.index = partIndex;
            	}

            	onDeport(partName, partIndex) {
            		delete this.name;
            		delete this.index;
            	}

                inject(message) {
                    if(this.interpreter) this.interpreter.send(message.destPort+"::"+message.signal, message);
                    else message.defer();
                }
            
                defer(message) {
                    if(this.borderPorts[message.destPort])
                        this.borderPorts[message.destPort].defer(message);
                    else if(this.internalPorts[message.destPort])
                        this.internalPorts[message.destPort].defer(message);
                }
            
                deliver(message) {
                    if(message.destSlot === this.fqn) {
                        let self = this;
                        message.defer = function() { self.defer(message); }
                        this.inject(message);
                    }
                    else {
                        let destSlot = null;
                        let longestPrefix = -1;
                        for(let part in this.parts) {
                            for(let slotIdx=0; slotIdx < this.parts[part].slots.length; slotIdx++) {
                                let slot = this.parts[part].slots[slotIdx]
                                let fqn = slot.getCustomParameters().fqn;
                                if(message.destSlot.startsWith(fqn)) {
                                    if (fqn.length > longestPrefix) {
                                        longestPrefix = fqn.length;
                                        destSlot = slot;
                                    }
                                }
                            }
                        }
                        if(destSlot != null)
                        destSlot.send('deliver', message);
                        else if(this.actor.getParent() != null)
                        this.actor.getParent().send('deliver', message);
                        else
                        console.log(this.getFQN()+"\t\t\tunexpected message: "+JSON.stringify(message))
                    }
                }

            	getName() {
            		return this.name+'['+this.index+']';
            	}
            
                getFQN() {
                    return this.fqn;
                }

            	getIndex() {
            		return this.index;
            	}

            	save() {
            		if(this.currentState)
            			return JSON.stringify(this.currentState);
            		return null;
            	}

            	run(initialState) {
            		if(this.stateMachine) {
                        ${if(debug) """
                            let debug = this.actor.actor ? this.actor.actor.config.debug : this.actor.config.debug;
                            if(debug === 'undefined') debug = false;
                            debug &= $debug
                        """.trimIndent() else ""}
                        
            			this.stateMachine['id'] = this.getFQN();
            			let machine = Machine(this.stateMachine);
            			this.currentState = initialState ?
            				machine.resolveState(State.create(JSON.parse(initialState))) : null;
                        
            			this.interpreter = interpret(machine ${if(debug)", { devTools: debug }" else ""}).onTransition(state => {
            				this.currentState  = state;
            			});

            			if(this.currentState)
            				this.interpreter.start(this.currentState);
            			else
            				this.interpreter.start();

            			for (let port in this.internalPorts)
            				this.internalPorts[port].recallAll();
            			for (let port in this.borderPorts)
            				this.borderPorts[port].recallAll();
            		}

            		for (let name in this.parts)
            			for(let i=0; i<this.parts[name].slots.length; i++)
            				if(!this.parts[name].optional && !this.parts[name].plugin)
            					this.parts[name].slots[i].send('run');
            	}
            }
        """.trimIndent()
    }

    private fun generatePartClass(): String {
        return """
            module.exports = class Part {
            	constructor(name, type, optional = false, plugin = false, replication = 1, borderPorts = {}) {
            		this.name = name;
            		this.type = type;
            		this.optional = optional;
            		this.plugin = plugin;
            		this.replication = replication;
            		this.borderPorts = borderPorts;
            		this.slots = Array(this.replication).fill(0);
            		this.slotReserved = Array(this.replication).fill(0);
            		this.locations = Array(this.replication).fill(0);
            	}

            	add(instance, index = -1) {
            		if(index == -1) {
            			index = this.getNextAvailableSlotIndex();
            			if(index == -1)
            				return false;
            		}

            		// slot occuppied!
            		if(this.slots[index])
            			return -1;

            		this.slots[index] = instance;
            		return index;
            	}

            	remove(index) {
            		if(index < 0 && index >= this.slots.length)
            			return false;

            		this.slots[index] = 0;
            		this.slotReserved[index] = 0;

            		return true;
            	}

            	getNextAvailableSlotIndex() {
            		for(let i=0; i<this.replication; i++)
            			if(!this.slots[i] && !this.slotReserved[i])
            				return i;
            		return -1;
            	}

            	reserveNextAvailableSlotIndex() {
            		let index = this.getNextAvailableSlotIndex();
            		if(index != -1)
            			this.slotReserved[index] = 1;
            		return index;
            	}

            	isFull() {
            		return this.getNextAvailableSlotIndex() == -1
            	}
            }
        """.trimIndent()
    }

    private fun generateTimespec(): String {
        return """
            module.exports =  class Timespec {
                constructor(sec, nsec = 0) {
                    this._nsec_per_sec = 1000000000;
            
                    this.sec = sec;
                    this.nsec = nsec;
                }
            
                static get(sec = 0, nsec = 0) {
                    return new Timespec(sec, nsec);
                }
            
                static now() {
                    let hrTime = process.hrtime();
                    return Timespec.get(hrTime[0], hrTime[1]);
                }
            
                add(operand) {
                    return Timespec.get(this.sec + operand.sec, this.nsec + operand.nsec)._normalize();
                }
            
                subtract(operand) {
                    return Timespec.get(this.sec - operand.sec, this.nsec - operand.nsec)._normalize();
                }
            
                greater(operand) {
                    return this.toNano() > operand.toNano();
                }
            
                less(operand) {
                    return this.toNano() < operand.toNano();
                }
            
                equals(operand) {
                    return this.toNano() == operand.toNano();
                }
            
                toString() {
                    return '(' + this.sec + ',' + this.nsec + ')';
                }
            
                toNano() {
                    return this.sec * this._nsec_per_sec + this.nsec;
                }
            
                _normalize() {
                    for(;;) {
                        if( this.sec < 0 ) {
                            if(-this._nsec_per_sec < this.nsec && this.nsec <= 0)
                                break;
            
                            this.sec  += this.nsec / this._nsec_per_sec;
                            this.nsec %= this._nsec_per_sec;
            
                            if(this.nsec > 0) {
                                this.sec  += 1;
                                this.nsec -= this._nsec_per_sec;
                            }
                        }
                        else if(this.sec > 0) {
                            if(0 <= this.nsec && this.nsec < this._nsec_per_sec)
                                break;
            
                            this.sec  += this.nsec / this._nsec_per_sec;
                            this.nsec %= this._nsec_per_sec;
            
                            if(this.nsec < 0) {
                                this.sec  -= 1;
                                this.nsec += this._nsec_per_sec;
                            }
                        }
                        else {
                            if(-this._nsec_per_sec < this.nsec && this.nsec < this._nsec_per_sec)
                                break;
            
                            this.sec  += this.nsec / this._nsec_per_sec;
                            this.nsec %= this._nsec_per_sec;
                        }
                    }
                    return this;
                }
            }
        """.trimIndent()
    }

    private fun generateLogProtocol(): String {
        return """
            let format = require('util').format

            module.exports = {
            	base: function(port, stream = process.stdout) {
            		this.port = port;
            		this.stream = stream;

            		this.space = function() {
            			return ' ';
            		};

            		this.tab = function() {
            			return '\t';
            		};

            		this.cr = function() {
            			return '\r';
            		};

            		this.crtab = function(i) {
            			this.show('\n');
            			while(--i >= 0)
            				this.tab();
            		};

            		this.log = function() {
            			stream.write(format.apply(null, arguments)+'\n');
            		};

            		this.show = function() {
            			stream.write(format.apply(null, arguments));
            		};

            		this.clear = function() {};
            		this.commit = function() {};
            	}
            }
        """.trimIndent()
    }

    private fun generateTimingProtocol(): String {
        return """
            const NanoTimer = require('nanotimer');
            const Timespec = require('./Timespec');
            const Signal = require('./Signal');
    
            module.exports = {
                base: function(port) {
                    this.port = port;
                    this.timers = {};
    
                    this.informIn = function(tspec, customParam) {
                        let id = this._genID();
                        this.timers[id] = new NanoTimer();
                        this.timers[id].setTimeout(this._callback, [this.port, customParam], tspec.toNano()+'n');
                        return id;
                    };
    
                    this.informAt = function(tspec, customParam) {
                        let id = this._genID();
                        this.timers[id] = new NanoTimer();
                        this.timers[id].setTimeout(this._callback, [this.port, customParam], tspec.subtract(Timespec.now()).toNano()+'n');
                        return id;
                    };
    
                    this.informEvery = function(tspec, customParam) {
                        let id = this._genID();
                        this.timers[id] = new NanoTimer();
                        this.timers[id].setInterval(this._callback, [this.port, customParam], tspec.toNano()+'n');
                        return id;
                    };
    
                    this.cancelTimer = function(tid) {
                        if(tid in this.timers) {
                            this.timers[tid].clearTimeout();
                            this.timers[tid].clearInterval();
                        }
    
                        delete this.timers[tid];
                    };
    
                    this._genID = function () {
                        return Math.random().toString(36).substr(2, 9);
                    };
    
                    this._callback = function(port, customParam) {
                        port.sendToSelf(new Signal(port, 'timeout', customParam));
                    };
                }
            }
        """.trimIndent()
    }

    private fun generateFrameProtocol(): String {
        return """
            const FrameService = require('./FrameService');

            module.exports = {
            	base: function(port) {
            		this.port = port;
            	
            		this.migrate = function(part, location, index) {
            			return FrameService.migrate(port.capsule.actor, part, location, index);
            		};

            		this.incarnate = function(type, data = {}) {
            			return FrameService.incarnate(port.capsule.actor, type, data);
            		};
            	
            		this.incarnateAt = function(part, type, data = {}, index = -1) {
            			return FrameService.incarnateAt(port.capsule.actor, part, type, data, index);
            		};
            	
            		this.import = function(instance, part, index = -1) {
            			FrameService.import(port.capsule.actor, instance, part, index);
            		};
            	
            		this.deport = function(part, index = -1) {
            			FrameService.deport(port.capsule.actor, part, index);
            		};
            	}
            }
        """.trimIndent()
    }

    private fun generateFrameService(): String {
        return """
            module.exports = class FrameService {
              static migrate(actor, part, location, index = -1) {
               	if(index >= part.locations.length)
                  return false;

                if(index != -1) {
                  if(part.locations[index]
            	    && part.locations[index].mode == location.mode
            	    && part.locations[index].host == location.host)
            	    return false;
            	
            		part.locations[index] = location;
            		
            		if(part.slots[index]) {
            			return part.slots[index].sendAndReceive('save').then(oldState => {
            				let config = part.slots[index].actor.config;
            			    config['mode'] = part.locations[index].mode;
            			    config['host'] = part.locations[index].host;
            				return part.slots[index].changeConfiguration(config).then(newActor => {
            			      actor.sendAndReceive('wireSlot', part.name, index).then(reply => {
            	                part.slots[index].send('run', oldState);
            	              });
            			      return newActor;
            	            });
            			});
            		}
            		
            		return true;
            	}

            	let promisses = [];
                for(let i=0; i<part.replication; i++)
                  promisses.push(FrameService.migrate(actor, part, location, i));
                return Promise.all(promisses);
              }

              static import(actor, instance, part, index = -1) {
            	if(!part.plugin)
                  return false;

                if(part.isFull())
                  return false;

                if(index >= part.slots.length)
                  return false;

                if(index == -1)
                  index = part.reserveNextAvailableSlotIndex();

                return actor.createChild(instance, {
                  name: part.name+'['+index+']',
                  customParameters: {
                    name: part.name,
                    index: index
                  }
                }).then(childActor => {
                  part.add(childActor, index);
                  actor.sendAndReceive('wireSlot', part.name, index).then(reply => {
                    //childActor.send('run');
                  });
                  return childActor;
                });
              }

              static deport(actor, part, index = -1) {
                if(!part.plugin)
                  return false;

                if(index != -1 && part.slots[index]) {
                  return actor.sendAndReceive('unwireSlot', part.name, index).then(reply => {
                    part.remove(index);
                  });
                }

                let promisses = [];
                for(let i=0; i<part.slots.length; i++)
                  promisses.push(FrameService.deport(actor, part, i));
                return Promise.all(promisses);
              }

              static incarnate(actor, type, data = {}) {
                return actor.createChild(type.modulePath(), {
                  customParameters: {
                    data: data
                  }
                });
              }

              static incarnateAt(actor, part, type, data = {}, index = -1) {
            	if(!part.optional && !part.plugin)
                  return false;

            	if(type != part.capsule)
                  return false;

                if(part.isFull())
                  return false;

                if(index >= part.slots.length)
                  return false;

                if(index == -1)
                  index = part.reserveNextAvailableSlotIndex();

                return actor.createChild(type.modulePath(), {
                  name: part.name+'['+index+']',
                  customParameters: {
                    name: part.name,
                    index: index,
                    data: data
                  }
                }).then(childActor => {
                  part.add(childActor, index);
                  actor.sendAndReceive('wireSlot', part.name, index).then(reply => {
                    childActor.send('run');
                  });
                  return childActor;
                });
              }

              static destroy(instance) {
                instance.destroy();
              }
            }
        """.trimIndent()
    }
}
