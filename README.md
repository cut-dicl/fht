# FHT: Federated Hoeffding Tree Framework

The Federated Hoeffding Tree (FHT) is a novel structural model aggregation framework for online learning from distributed data streams.

The `ParserAndEvaluator.java` runs a prequential (test-then-train) evaluation of Hoeffding Trees spread over `M` simulated nodes, with the nodes periodically exchanging and merging their models. It compares five federation modes on the same stream, and reports accuracy, F1, communication volume and merge CPU time.

## 1. Layout and Build

```
ht/        Maven project: the evaluator (federated.parsers.ParserAndEvaluator), the
           FederatedHoeffdingTree and its helper classes
traces/    the datasets (section 2)
```

Requirements: JDK 16 or newer and Maven. MOA and the other dependencies are declared in
`ht/pom.xml`.

Build from inside `ht/`, then go back to the repository root to run experiments:

```bash
cd ht
mvn package
cd ..
```

The compiled classes are in `ht/target/classes` and the dependency jars are listed in
`ht/cp.txt`, so every run uses the classpath `ht/target/classes:$(cat ht/cp.txt)`. 

To generate the `cp.txt` file, run from inside `ht/` the command:

On Linux:
`mvn dependency:build-classpath -Dmdep.outputFile=cp.txt`

On Windows:
`mvn dependency:build-classpath "-Dmdep.outputFile=cp.txt"`

## 2. Supported Datasets

*Important:* You need to unzip the files in the `traces` folder before using them.

| File in `traces/` | Instances | Features | Classes | Source |
|---|---|---|---|---|
| `Airlines.arff` | 539,383 | 7 | 2 | [MOA datasets](https://moa.cms.waikato.ac.nz/datasets/) |
| `ForestCovertypeNorm.arff` | 581,012 | 54 | 7 | [MOA datasets](https://moa.cms.waikato.ac.nz/datasets/) |
| `PokerHandNorm.arff` | 829,201 | 10 | 10 | [MOA datasets](https://moa.cms.waikato.ac.nz/datasets/) |
| `IntrusionDetection.csv` | 494,020 | 41 | 23 | [KDD Cup 1999 (CSV)](https://www.kaggle.com/datasets/venkatakanumuru/kddcup99csv) |
| `Gas.txt` | 13,910 | 128 | 6 | [UCI Gas Sensor Array Drift](https://archive.ics.uci.edu/dataset/224/gas+sensor+array+drift+dataset), the 10 batch files combined into one |

## 3. Command Line

All arguments are positional. Every argument after the first is optional, but an argument can only be given if all the ones before it are given too.

*Important:* On Windows, you need to replace `:` with `;` in the classpath argument.

```
java -cp "ht/target/classes:$(cat ht/cp.txt)" federated.parsers.ParserAndEvaluator \
     <dataset> [report] [M] [seed] [mode] [backAndForth] [fedEnabled] \
     [thrBase] [thrStep] [thrCap] [triggerPolicy] [interval] [dirichlet] [alpha] [periodicReport]
```

| # | Argument | Default | Meaning |
|---|---|---|---|
| 1 | `dataset` | required | path to one of the files in section 2 |
| 2 | `report` | `prequential_report.txt` | path of the output report file |
| 3 | `M` | `3` | number of nodes (models) |
| 4 | `seed` | `42` | seed for the random or Dirichlet node assignment |
| 5 | `mode` | `GLOBAL` | `INDEPENDENT`, `GLOBAL`, `P2P`, `VOTING` (see section 4) |
| 6 | `backAndForth` | `true` | P2P only. `true`: ring 1...M-1 then back to 0. `false`: fixed random ring |
| 7 | `fedEnabled` | `true` | `false` disables all federation regardless of mode |
| 8 | `thrBase` | `200` | LOCAL_THRESHOLD only: instances a node must see before it asks for a round |
| 9 | `thrStep` | `100` | LOCAL_THRESHOLD only: added to the threshold after each round, up to `thrCap` |
| 10 | `thrCap` | `1000` | LOCAL_THRESHOLD only: upper bound of the threshold |
| 11 | `triggerPolicy` | `LOCAL_THRESHOLD` | `LOCAL_THRESHOLD` or `GLOBAL_INTERVAL`. Must be given together with argument 12 |
| 12 | `interval` | `1000` | GLOBAL_INTERVAL only: one round every interval instances across all nodes |
| 13 | `dirichlet` | `false` | `true`: non-IID node assignment (section 5) |
| 14 | `alpha` | `1.0` | Dirichlet concentration; smaller means more skewed |
| 15 | `periodicReport` | `off` | `off`, `on` (a metric block every 1000 instances) or a number N (a block every N instances) |

Running with no arguments prints the usage line and exits.


## 4. Federation Modes

All modes use the same stream and under `GLOBAL_INTERVAL`,
the same round instants, so their numbers are directly comparable.

**CENTRAL.** Run with `M=1` and mode `INDEPENDENT`, so a single tree is trained on the whole stream.

**INDEPENDENT.** Every node trains its own tree. No communication. The distributed baseline.

**GLOBAL.** Client–server. At each round, every node uploads a copy of its 
tree to the orchestrator, which merges the `M` copies into one tree and sends
that tree back to every node, which adopts it. Per round: `M` uploads 
and `M` downloads.

**P2P.** The first node snapshots its tree and sends it to node 1.
Each node merges its own tree into the travelling model, adopts the result,
and forwards it. With `backAndForth=true` the model goes 0->1->...->M-1 and 
then back M-1->...->1->0, merging at every stop in both directions, so that
the nodes visited early also receive the fully merged model. 
Per round: `2(M-1)` transfers. While a round is in progress, every node is
marked busy and ignores new round requests; the originator broadcasts UNLOCK
when the model returns to it, which clears the flag on every node.

**VOTING.** Same schedule and upload as GLOBAL, no merge. Each node uploads a 
snapshot and receives the `M-1` other snapshots, which are frozen until the 
next exchange. A node predicts by soft voting: its own live tree plus the 
`M-1` copies each contribute their class distribution normalised to sum to one,
the vectors are added, and the arg-max is the prediction. Each node keeps 
training only its own tree. Per round: `M` uploads and `M(M-1)` downloads.

**Round triggers.** With `GLOBAL_INTERVAL` a round starts every `interval` 
instances (counted over all nodes), initiated by node 0. With `LOCAL_THRESHOLD` 
each node keeps its own counter and asks for a round once it exceeds its 
threshold, which then grows by `thrStep` up to `thrCap`.

## 5. Node Assignment

Each instance goes to exactly one node, chosen before prediction.

* **Random (default).** Each instance is handed to a node drawn uniformly 
  from the `M` nodes, using `seed`. The seed changes only which node gets 
  which instance; the order in which instances are processed is always the
  same in the file order. The same `M` and `seed` give the same assignment 
  every time.
* **Dirichlet (`dirichlet=true`).** For every class, a probability vector 
  over the nodes is drawn once from a symmetric Dirichlet(`alpha`); instances 
  of that class are then routed according to it. `alpha=1000` is practically 
  IID, `alpha=0.1` is strongly skewed, with some classes almost absent from 
  some nodes. The vectors are logged when each class is first seen, and the 
  report ends with a class-by-node table of what each node actually received.

## 6. Examples with `Gas.txt`

Independent baseline, 5 nodes:

```bash
java -cp "ht/target/classes$(cat ht/cp.txt)" federated.parsers.ParserAndEvaluator traces/Gas.txt gas_indep_M5.txt 5 42 INDEPENDENT true true 200 100 1000 GLOBAL_INTERVAL 1000
```

Global FL, 5 nodes, one round every 1000 instances:

```bash
java -cp "ht/target/classes:$(cat ht/cp.txt)" federated.parsers.ParserAndEvaluator traces/Gas.txt gas_global_M5.txt 5 42 GLOBAL true true 200 100 1000 GLOBAL_INTERVAL 1000
```

P2P ring, 5 nodes, one round every 1000 instances:

```bash
java -cp "ht/target/classes:$(cat ht/cp.txt)" federated.parsers.ParserAndEvaluator traces/Gas.txt gas_p2p_M5.txt 5 42 P2P true true 200 100 1000 GLOBAL_INTERVAL 1000
```

Voting, 5 nodes, one round every 1000 instances:

```bash
java -cp "ht/target/classes:$(cat ht/cp.txt)" federated.parsers.ParserAndEvaluator traces/Gas.txt gas_voting_M5.txt 5 42 VOTING true true 200 100 1000 GLOBAL_INTERVAL 1000
```

Non-IID split, 20 nodes, Dirichlet alpha 0.1, with an intermediate metrics block every 1000 instances:

```bash
java -cp "ht/target/classes:$(cat ht/cp.txt)" federated.parsers.ParserAndEvaluator traces/Gas.txt gas_p2p_M20_a01.txt 20 42 P2P true true 200 100 1000 GLOBAL_INTERVAL 1000 true 0.1 on
```

Local-threshold trigger instead of a fixed interval (each node asks after 200 instances, threshold growing by 100 per round up to 1000):

```bash
java -cp "ht/target/classes:$(cat ht/cp.txt)" federated.parsers.ParserAndEvaluator traces/Gas.txt gas_p2p_local.txt 5 42 P2P true true 200 100 1000 LOCAL_THRESHOLD 1000
```

Centralized, 1 node:

```bash
java -cp "ht/target/classes:$(cat ht/cp.txt)" federated.parsers.ParserAndEvaluator traces/Gas.txt gas_central.txt 1 42 INDEPENDENT true true 200 100 1000 GLOBAL_INTERVAL 1000
```

## 7. What the Output Report Contains

The report is written to `<report>`. The console shows only the loader line and the TOTAL section. By default the report contains the header, the FINAL section and the TOTAL section; argument 15 adds the periodic metrics blocks.


1. **Header.** Dataset name, source path, instance and attribute counts, feature 
  ist, class values; model hyper-parameters; `M`, assignment (`random` or 
  `dirichlet` with alpha), federation settings, trigger policy and the 
  periodic-report setting.
2. **Periodic metrics blocks** (only if argument 15 is set): `GLOBAL Seen=N` 
  followed by one line per node with cumulative accuracy, macro/micro/weighted 
  precision, recall and F1, sliding-window accuracy (last 100 instances) and 
  merge CPU so far.
3. **FINAL.** Per node: the same metrics, instances assigned, `BytesSent`, 
  `BytesReceived`, `MergeRounds` (rounds the node took part in) and `FinalModelSize`.
4. **Class-by-node tables.** Counts and shares of each class received by each node.
5. **TOTAL.** Pooled metrics over all nodes, then
   `Global merge CPU time`, `Nodes merge CPU time (sum)`, `Total merge CPU time`, and
   `Total Communication | BytesSent | BytesReceived | MergeRounds`.

## 8. Citing FHT

George Savva, Mohammed Elbamby, and Herodotos Herodotou. FHT: Federated Hoeffding Trees for Distributed Stream Mining. In Proceedings of 33rd ACM SIGKDD Conference on Knowledge Discovery and Data Mining (KDD '27). ACM, New York, NY, USA

## 9. Authors

George Savva\
Cyprus University of Technology, Cyprus

Herodotos Herodotou\
Cyprus University of Technology, Cyprus\
https://dicl.cut.ac.cy/
