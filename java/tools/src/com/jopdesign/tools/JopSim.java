
/**
*	JopSim.java
*
*	Simulation of JOP JVM.
*
*		difference between JOP and JopSim:
*			loadBc (and invokestatic)
*
*		2001-12-03	I don't need a fp!?
*/

package com.jopdesign.tools;

import java.io.*;
import java.util.*;
import com.jopdesign.sys.*;

public class JopSim {

	static final int MAX_MEM = 1024*1024/4;
	static final int MAX_STACK = 256;	// with internal memory

	static final int SYS_INT = 0xf0;

	int[] mem_load = new int[MAX_MEM];
	int[] mem = new int[MAX_MEM];
	int[] stack = new int[MAX_STACK];
	Cache cache;

	int pc, cp, vp, sp, mp;
	int heap;
	int jjp;
	int jjhp;
	int moncnt;

	int empty_heap;

	static boolean log = false;
	static boolean useHandle = false;

	//
	//	simulate timer interrupt
	//
	static int nextTimerInt;
	static boolean intPend;
	static boolean interrupt;
	static boolean intEna;

	//
	//	only for statistics
	//
	int ioCnt;

	int[] bcStat = new int[256];
	int rdMemCnt;
	int wrMemCnt;
	int maxInstr;
	int instrCnt;
	int maxSp;

	JopSim(String fn, int max) {
		maxInstr = max;
		init(fn);
	}

	JopSim(String fn) {
		this(fn, 0);
	}

	void init(String fn) {

		heap = 0;
		moncnt = 1;

		try {
			StreamTokenizer in = new StreamTokenizer(new FileReader(fn));

			in.wordChars( '_', '_' );
			in.wordChars( ':', ':' );
			in.eolIsSignificant(true);
			in.slashStarComments(true);
			in.slashSlashComments(true);
			in.lowerCaseMode(true);

			
			while (in.nextToken()!=StreamTokenizer.TT_EOF) {
				if (in.ttype == StreamTokenizer.TT_NUMBER) {
					mem_load[heap++] = (int) in.nval;
				}
			}

		} catch (IOException e) {
			System.out.println(e.getMessage());
			System.exit(-1);
		}

		int instr = mem_load[0];
		System.out.println("Program: "+fn);
		System.out.println(instr + " instruction word ("+(instr*4/1024)+" KB)");
		System.out.println(heap + " words mem read ("+(heap*4/1024)+" KB)");
		empty_heap = heap;

		cache = new Cache(mem, this);

	}

	void start() {

		ioCnt = 0;
		rdMemCnt = 0;
		wrMemCnt = 0;
		instrCnt = 0;
		maxSp = 0;
		for (int i=0; i<256; ++i) bcStat[i] = 0;

		heap = empty_heap;
		for (int i=0; i<heap; ++i) mem[i] = mem_load[i];
		moncnt = 1;

		nextTimerInt = 0;
		intPend = false;
		interrupt = false;
		intEna = false;

		pc = vp = 0;
		sp = 128;
		int ptr = readMem(0);
		jjp = readMem(ptr+1);
		jjhp = readMem(ptr+2);

		invokestatic(ptr);			// load main()
	}

/**
*	'debug' functions.
*/
	void noim(int instr) {

		
		invoke(jjp+(instr<<1));
/*
		System.out.println("byte code "+JopInstr.name(instr)+" ("+instr+") not implemented");
System.out.println(mp+" "+pc);
		System.exit(-1);
*/
	}

/**
*	call function in JVM.java with constant on stack
*/
	void jjvmConst(int instr) {

		int idx = readOpd16u();
		int val = readMem(cp+idx);			// read constant
// System.out.println("jjvmConst: "+instr+" "+(cp+idx)+" "+val);
		stack[++sp] = val;					// push on stack
		invoke(jjp+(instr<<1));
	}

	void dump() {
		System.out.print("cp="+cp+" vp="+vp+" sp="+sp+" pc="+pc);
		System.out.println(" Stack=[..., "+stack[sp-2]+", "+stack[sp-1]+", "+stack[sp]+"]");
	}

/**
*	helper functions.
*/
	int readInstrMem(int addr) {

// System.out.println(addr+" "+mem[addr]);
		ioCnt += 12;

		if (addr>MAX_MEM || addr<0) {
			System.out.println("readInstrMem: wrong address: "+addr);
			System.exit(-1);
		}

		return mem[addr];
	}
	int readMem(int addr) {

// System.out.println(addr+" "+mem[addr]);
		rdMemCnt++;
		ioCnt += 12;

		if (addr>MAX_MEM || addr<0) {
			System.out.println("readMem: wrong address: "+addr);
			System.exit(-1);
		}

		return mem[addr];
	}
	void writeMem(int addr, int data) {

		wrMemCnt++;
		ioCnt += 12;

		if (addr>MAX_MEM || addr<0) {
			System.out.println("writeMem: wrong address: "+addr);
			System.exit(-1);
		}

		mem[addr] = data;
	}

	int readOpd16u() {

		int idx = ((cache.bc(pc)<<8) | (cache.bc(pc+1)&0x0ff)) & 0x0ffff;
		pc += 2;
		return idx;
	}

	int readOpd16s() {

		int i = readOpd16u();
		if ((i&0x8000) != 0) {
			i |= 0xffff0000;
		}
		return i;
	}

	int readOpd8s() {

		return cache.bc(pc++);
	}

	int readOpd8u() {

		return cache.bc(pc++)&0x0ff;
	}

	int usCnt() {
		return ((int) System.currentTimeMillis())*1000;
	}

	void sysRd() {

		int addr = stack[sp];

		try {
			switch (addr) {
				case Const.IO_STATUS:
					stack[sp] = Const.MSK_UA_TDRE;
					if (System.in.available()!=0) {
						stack[sp] |= Const.MSK_UA_RDRF;
					}
					break;
				case Const.IO_UART:
					if (System.in.available()!=0) {
						stack[sp] = System.in.read();
					} else {
						stack[sp] = '_';
					}
					break;
				case Const.IO_CNT:
					stack[sp] = ioCnt;
					break;
				case Const.IO_US_CNT:
					stack[sp] = usCnt();
					break;
				case 1234:
					// trigger cache debug output
//					cache.rawData();
//					cache.resetCnt();
				default:
					stack[sp] = 0;
			}
		} catch (Exception e) {
			System.out.println(e);
		}
	}

	void sysWr() {

		int addr = stack[sp--];
		int val = stack[sp--];
		switch (addr) {
			case Const.IO_UART:
				if (log) System.out.print("\t->");
				System.out.print((char) val);
				if (log) System.out.println("<-");
				break;
			case Const.IO_INT_ENA:
				intEna = (val==0) ? false : true;
				break;
			case Const.IO_TIMER:
				intPend = false;		// reset pending interrupt
				interrupt = false;		// for shure ???
				nextTimerInt = val;
				break;
			case Const.IO_SWINT:
				if (!intPend) {
					interrupt = true;
					intPend = true;
				}
				break;
			default:
		}
	}
//
//	start of JVM :-)
//
	void invokespecial() {
		invokestatic();				// what's the difference?
	}

	void invokevirtual() {

		int idx = readOpd16u();
		int off = readMem(cp+idx);	// index in vt and arg count (-1)
		int args = off & 0xff;		// this is args count without obj-ref
		off >>>= 8;
		int ref = stack[sp-args];
		if (useHandle) {
			// handle needs indirection
			ref = readMem(ref);
		}
		int vt = readMem(ref-1);
// System.out.println("invvirt: off: "+off+" args: "+args+" ref: "+ref+" vt: "+vt+" addr: "+(vt+off));
		invoke(vt+off);
	}

	void invokeinterface() {

		int idx = readOpd16u();
		readOpd16u();				// read historical argument count and 0

		int off = readMem(cp+idx);			// index in interface table

		int args = off & 0xff;				// this is args count without obj-ref
		off >>>= 8;
		int ref = stack[sp-args];
		if (useHandle) {
			// handle needs indirection
			ref = readMem(ref);
		}

		int vt = readMem(ref-1);			// pointer to virtual table in obj-1
		int it = readMem(vt-1);				// pointer to interface table one befor vt

		int mp = readMem(it+off);
// System.out.println("invint: off: "+off+" args: "+args+" ref: "+ref+" vt: "+vt+" mp: "+(mp));
		invoke(mp);
	}
/**
*	invokestatic wie es in der JVM sein soll!!!
*/
	void invokestatic() {

		int idx = readOpd16u();
		invokestatic(cp+idx);
	}


	void invokestatic(int ptr) {

		invoke(readMem(ptr));
	}

/**
*	do the real invoke. called with a pointer to method struct.
*/
	void invoke(int new_mp) {

		if (log) {
			System.out.println("addr. of meth.struct="+new_mp);		
		}
		int old_vp = vp;
		int old_cp = cp;
		int old_mp = mp;

		mp = new_mp;

		int start = readMem(mp);
		int len = start & 0x03ff;
		start >>>= 10;
		cp = readMem(mp+1);
		int locals = (cp>>>5) & 0x01f;
		int args = cp & 0x01f;
		cp >>>= 10;

		int old_sp = sp-args;
		vp = old_sp+1;
		sp += locals;
// System.out.println("inv: start: "+start+" len: "+len+" locals: "+locals+" args: "+args+" cp: "+cp);

		stack[++sp] = old_sp;
		stack[++sp] = cache.corrPc(pc);
		stack[++sp] = old_vp;
		stack[++sp] = old_cp;
		stack[++sp] = old_mp;

		pc = cache.invoke(start, len);
	}

/**
*	return wie es sein sollte (oder doch nicht?)
*/
	void vreturn() {

		mp = stack[sp--];
		cp = stack[sp--];
		vp = stack[sp--];
		pc = stack[sp--];
		sp = stack[sp--];

		int start = readMem(mp);
		int len = start & 0x03ff;
		start >>>= 10;
		// cp = readMem(mp+1)>>>10;

		pc = cache.ret(start, len, pc);
	}

	void ireturn() {

		int val = stack[sp--];
		vreturn();
		stack[++sp] = val;
	}

	void lreturn() {

		int val1 = stack[sp--];
		int val2 = stack[sp--];
		vreturn();
		stack[++sp] = val2;
		stack[++sp] = val1;
	}

/**
*
*/
	void putstatic() {

		int idx = readOpd16u();
		int addr = readMem(cp+idx);	// not now
// System.out.println("putstatic address: "+addr+" TOS: "+stack[sp]);
		writeMem(addr, stack[sp--]);
	}

	void getstatic() {

		int idx = readOpd16u();
		int addr = readMem(cp+idx);	// not now
// System.out.println("getstatic address: "+addr+" TOS: "+stack[sp]);
		stack[++sp] = readMem(addr);
	}

	void putfield() {

		int idx = readOpd16u();
		int off = readMem(cp+idx);
		int val = stack[sp--];
		int ref = stack[sp--];
		if (useHandle) {
			// handle needs indirection
			ref = readMem(ref);
		}
		writeMem(ref+off, val);
	}

	void getfield() {

		int idx = readOpd16u();
		int off = readMem(cp+idx);
		int ref = stack[sp];
		if (useHandle) {
			// handle needs indirection
			ref = readMem(ref);
		}
		stack[sp] = readMem(ref+off);
	}

/**
*	the simulaton.
*
*	sp points to TOS
*/
	void interpret() {

		int new_pc;		// for cond. branches
		int ref, val, idx;
		int old_pc = -1;
		int old_mp = -1;

		int a, b, c, d;

		for (;;) {

//
//	check for endless loop and stop
//
/*
			if (pc==old_pc && mp==old_mp) {
				System.out.println();
				System.out.println("endless loop");
				break;
			}
			old_pc = pc;
			old_mp = mp;
*/
			if (maxInstr!=0 && instrCnt>=maxInstr) {
				break;
			}

//
//	statistic
//
			++instrCnt;
			if (sp > maxSp) maxSp = sp;


			int instr = cache.bc(pc++) & 0x0ff;

//
//	interrupt handling
//
			if ((nextTimerInt-usCnt()<0) && !intPend) {
				intPend = true;
				interrupt = true;
			}
			if (interrupt && intEna) {
				instr = SYS_INT;
				interrupt = false;		// reset int
			}

// stat
			bcStat[instr]++;
			ioCnt += JopInstr.cnt(instr);

			String spc = (pc-1)+" ";
			while (spc.length()<4) spc = " "+spc;
			String s = spc+JopInstr.name(instr);
			if (log) {
				System.out.print(s+"\t");
				dump();
			}

			switch (instr) {

				case 0 :		// nop
					break;
				case 1 :		// aconst_null
					stack[++sp] = 0;
					break;
				case 2 :		// iconst_m1
					stack[++sp] = -1;
					break;
				case 3 :		// iconst_0
					stack[++sp] = 0;
					break;
				case 4 :		// iconst_1
					stack[++sp] = 1;
					break;
				case 5 :		// iconst_2
					stack[++sp] = 2;
					break;
				case 6 :		// iconst_3
					stack[++sp] = 3;
					break;
				case 7 :		// iconst_4
					stack[++sp] = 4;
					break;
				case 8 :		// iconst_5
					stack[++sp] = 5;
					break;
				case 9 :		// lconst_0
					stack[++sp] = 0;
					stack[++sp] = 0;
					break;
				case 10 :		// lconst_1
					stack[++sp] = 0;
					stack[++sp] = 1;
					break;
				case 11 :		// fconst_0
					noim(11);
					break;
				case 12 :		// fconst_1
					noim(12);
					break;
				case 13 :		// fconst_2
					noim(13);
					break;
				case 14 :		// dconst_0
					noim(14);
					break;
				case 15 :		// dconst_1
					noim(15);
					break;
				case 16 :		// bipush
					stack[++sp] = readOpd8s();
					break;
				case 17 :		// sipush
					stack[++sp] = readOpd16s();
					break;
				case 18 :		// ldc
					stack[++sp] = readMem(cp+readOpd8u());
					break;
				case 19 :		// ldc_w
					stack[++sp] = readMem(cp+readOpd16u());
					break;
				case 20 :		// ldc2_w
					idx = readOpd16u();
					stack[++sp] = readMem(cp+idx);
					stack[++sp] = readMem(cp+idx+1);
					break;
				case 25 :		// aload
				case 23 :		// fload
				case 21 :		// iload
					idx = readOpd8u();
					stack[++sp] = stack[vp+idx];
					break;
				case 22 :		// lload
					idx = readOpd8u();
					stack[++sp] = stack[vp+idx];
					stack[++sp] = stack[vp+idx+1];
					break;
				case 24 :		// dload
					idx = readOpd8u();
					stack[++sp] = stack[vp+idx];
					stack[++sp] = stack[vp+idx+1];
					break;
				case 42 :		// aload_0
				case 34 :		// fload_0
				case 26 :		// iload_0
					stack[++sp] = stack[vp];
					break;
				case 43 :		// aload_1
				case 35 :		// fload_1
				case 27 :		// iload_1
					stack[++sp] = stack[vp+1];
					break;
				case 44 :		// aload_2
				case 36 :		// fload_2
				case 28 :		// iload_2
					stack[++sp] = stack[vp+2];
					break;
				case 45 :		// aload_3
				case 37 :		// fload_3
				case 29 :		// iload_3
					stack[++sp] = stack[vp+3];
					break;
				case 30 :		// lload_0
					stack[++sp] = stack[vp];
					stack[++sp] = stack[vp+1];
					break;
				case 31 :		// lload_1
					stack[++sp] = stack[vp+1];
					stack[++sp] = stack[vp+2];
					break;
				case 32 :		// lload_2
					stack[++sp] = stack[vp+2];
					stack[++sp] = stack[vp+3];
					break;
				case 33 :		// lload_3
					stack[++sp] = stack[vp+3];
					stack[++sp] = stack[vp+4];
					break;
				case 38 :		// dload_0
					noim(38);
					break;
				case 39 :		// dload_1
					noim(39);
					break;
				case 40 :		// dload_2
					noim(40);
					break;
				case 41 :		// dload_3
					noim(41);
					break;
				case 50 :		// aaload
				case 51 :		// baload
				case 52 :		// caload
				case 48 :		// faload
				case 46 :		// iaload
				case 53 :		// saload
					idx = stack[sp--];	// index
					ref = stack[sp--];	// ref
					if (useHandle) {
						// handle needs indirection
						ref = readMem(ref);
					}
					stack[++sp] = readMem(ref+idx);
					break;
				case 47 :		// laload
					noim(47);
					break;
				case 49 :		// daload
					noim(49);
					break;
				case 58 :		// astore
				case 56 :		// fstore
				case 54 :		// istore
					idx = readOpd8u();
					stack[vp+idx] = stack[sp--];
					break;
				case 55 :		// lstore
					idx = readOpd8u();
					stack[vp+idx+1] = stack[sp--];
					stack[vp+idx] = stack[sp--];
					break;
				case 57 :		// dstore
					idx = readOpd8u();
					stack[vp+idx+1] = stack[sp--];
					stack[vp+idx] = stack[sp--];
					break;
				case 75 :		// astore_0
				case 67 :		// fstore_0
				case 59 :		// istore_0
					stack[vp] = stack[sp--];
					break;
				case 76 :		// astore_1
				case 68 :		// fstore_1
				case 60 :		// istore_1
					stack[vp+1] = stack[sp--];
					break;
				case 77 :		// astore_2
				case 69 :		// fstore_2
				case 61 :		// istore_2
					stack[vp+2] = stack[sp--];
					break;
				case 78 :		// astore_3
				case 70 :		// fstore_3
				case 62 :		// istore_3
					stack[vp+3] = stack[sp--];
					break;
				case 63 :		// lstore_0
					stack[vp+1] = stack[sp--];
					stack[vp] = stack[sp--];
					break;
				case 64 :		// lstore_1
					stack[vp+2] = stack[sp--];
					stack[vp+1] = stack[sp--];
					break;
				case 65 :		// lstore_2
					stack[vp+3] = stack[sp--];
					stack[vp+2] = stack[sp--];
					break;
				case 66 :		// lstore_3
					stack[vp+4] = stack[sp--];
					stack[vp+3] = stack[sp--];
					break;
				case 71 :		// dstore_0
					noim(71);
					break;
				case 72 :		// dstore_1
					noim(72);
					break;
				case 73 :		// dstore_2
					noim(73);
					break;
				case 74 :		// dstore_3
					noim(74);
					break;
				case 83 :		// aastore
				case 84 :		// bastore
				case 85 :		// castore
				case 81 :		// fastore
				case 79 :		// iastore
				case 86 :		// sastore
					val = stack[sp--];	// value
					idx = stack[sp--];	// index
					ref = stack[sp--];	// ref
					if (useHandle) {
						// handle needs indirection
						ref = readMem(ref);
					}
					writeMem(ref+idx, val);
					break;
				case 80 :		// lastore
					noim(80);
					break;
				case 82 :		// dastore
					noim(82);
					break;
				case 87 :		// pop
					sp--;
					break;
				case 88 :		// pop2
					sp--;
					sp--;
					break;
				case 89 :		// dup
					val = stack[sp];
					stack[++sp] = val;
					break;
				case 90 :		// dup_x1
					a = stack[sp--];
					b = stack[sp--];
					stack[++sp] = a;
					stack[++sp] = b;
					stack[++sp] = a;
					break;
				case 91 :		// dup_x2
					a = stack[sp--];
					b = stack[sp--];
					c = stack[sp--];
					stack[++sp] = a;
					stack[++sp] = c;
					stack[++sp] = b;
					stack[++sp] = a;
					break;
				case 92 :		// dup2
					a = stack[sp--];
					b = stack[sp--];
					stack[++sp] = b;
					stack[++sp] = a;
					stack[++sp] = b;
					stack[++sp] = a;
					break;
				case 93 :		// dup2_x1
					noim(93);
					break;
				case 94 :		// dup2_x2
					noim(94);
					break;
				case 95 :		// swap
					noim(95);
					break;
				case 96 :		// iadd
					val = stack[sp-1] + stack[sp];
					stack[--sp] = val;
					break;
				case 97 :		// ladd
					noim(97);
					break;
				case 98 :		// fadd
					noim(98);
					break;
				case 99 :		// dadd
					noim(99);
					break;
				case 100 :		// isub
					val = stack[sp-1] - stack[sp];
					stack[--sp] = val;
					break;
				case 101 :		// lsub
					noim(101);
					break;
				case 102 :		// fsub
					noim(102);
					break;
				case 103 :		// dsub
					noim(103);
					break;
				case 104 :		// imul
					val = stack[sp-1] * stack[sp];
					stack[--sp] = val;
					break;
				case 105 :		// lmul
					noim(105);
					break;
				case 106 :		// fmul
					noim(106);
					break;
				case 107 :		// dmul
					noim(107);
					break;
				case 108 :		// idiv
					val = stack[sp-1] / stack[sp];
					stack[--sp] = val;
					break;
				case 109 :		// ldiv
					noim(109);
					break;
				case 110 :		// fdiv
					noim(110);
					break;
				case 111 :		// ddiv
					noim(111);
					break;
				case 112 :		// irem
					val = stack[sp-1] % stack[sp];
					stack[--sp] = val;
					break;
				case 113 :		// lrem
					noim(113);
					break;
				case 114 :		// frem
					noim(114);
					break;
				case 115 :		// drem
					noim(115);
					break;
				case 116 :		// ineg
					stack[sp] = -stack[sp];
					break;
				case 117 :		// lneg
					noim(117);
					break;
				case 118 :		// fneg
					noim(118);
					break;
				case 119 :		// dneg
					noim(119);
					break;
				case 120 :		// ishl
					val = stack[sp-1] << stack[sp];
					stack[--sp] = val;
					break;
				case 121 :		// lshl
					noim(121);
					break;
				case 122 :		// ishr
					val = stack[sp-1] >> stack[sp];
					stack[--sp] = val;
					break;
				case 123 :		// lshr
					noim(123);
					break;
				case 124 :		// iushr
					val = stack[sp-1] >>> stack[sp];
					stack[--sp] = val;
					break;
				case 125 :		// lushr
					noim(125);
					break;
				case 126 :		// iand
					val = stack[sp-1] & stack[sp];
					stack[--sp] = val;
					break;
				case 127 :		// land
					noim(127);
					break;
				case 128 :		// ior
					val = stack[sp-1] | stack[sp];
					stack[--sp] = val;
					break;
				case 129 :		// lor
					noim(129);
					break;
				case 130 :		// ixor
					val = stack[sp-1] ^ stack[sp];
					stack[--sp] = val;
					break;
				case 131 :		// lxor
					noim(131);
					break;
				case 132 :		// iinc
					idx = readOpd8u();
					stack[vp+idx] = stack[vp+idx]+readOpd8s();
					break;
				case 133 :		// i2l
					noim(133);
					break;
				case 134 :		// i2f
					noim(134);
					break;
				case 135 :		// i2d
					noim(135);
					break;
				case 136 :		// l2i
					val = stack[sp];	// low part
					--sp;				// drop high word
					stack[sp] = val;	// low on stack
					break;
				case 137 :		// l2f
					noim(137);
					break;
				case 138 :		// l2d
					noim(138);
					break;
				case 139 :		// f2i
					noim(139);
					break;
				case 140 :		// f2l
					noim(140);
					break;
				case 141 :		// f2d
					noim(141);
					break;
				case 142 :		// d2i
					noim(142);
					break;
				case 143 :		// d2l
					noim(143);
					break;
				case 144 :		// d2f
					noim(144);
					break;
				case 145 :		// i2b
					noim(145);
					break;
				case 146 :		// i2c
					stack[sp] = stack[sp] & 0x0ffff;
					break;
				case 147 :		// i2s
					noim(147);
					break;
				case 148 :		// lcmp
					noim(148);
					break;
				case 149 :		// fcmpl
					noim(149);
					break;
				case 150 :		// fcmpg
					noim(150);
					break;
				case 151 :		// dcmpl
					noim(151);
					break;
				case 152 :		// dcmpg
					noim(152);
					break;
				case 153 :		// ifeq
				case 198 :		// ifnull
					new_pc = pc-1;
					new_pc += readOpd16s();
					sp--;
					if (stack[sp+1] == 0) pc = new_pc;
					break;
				case 154 :		// ifne
				case 199 :		// ifnonnull
					new_pc = pc-1;
					new_pc += readOpd16s();
					sp--;
					if (stack[sp+1] != 0) pc = new_pc;
					break;
				case 155 :		// iflt
					new_pc = pc-1;
					new_pc += readOpd16s();
					sp--;
					if (stack[sp+1] < 0) pc = new_pc;
					break;
				case 156 :		// ifge
					new_pc = pc-1;
					new_pc += readOpd16s();
					sp--;
					if (stack[sp+1] >= 0) pc = new_pc;
					break;
				case 157 :		// ifgt
					new_pc = pc-1;
					new_pc += readOpd16s();
					sp--;
					if (stack[sp+1] > 0) pc = new_pc;
					break;
				case 158 :		// ifle
					new_pc = pc-1;
					new_pc += readOpd16s();
					sp--;
					if (stack[sp+1] <= 0) pc = new_pc;
					break;
				case 159 :		// if_icmpeq
				case 165 :		// if_acmpeq
					new_pc = pc-1;
					new_pc += readOpd16s();
					sp -= 2;
					if (stack[sp+1] == stack[sp+2]) pc = new_pc;
					break;
				case 160 :		// if_icmpne
				case 166 :		// if_acmpne
					new_pc = pc-1;
					new_pc += readOpd16s();
					sp -= 2;
					if (stack[sp+1] != stack[sp+2]) pc = new_pc;
					break;
				case 161 :		// if_icmplt
					new_pc = pc-1;
					new_pc += readOpd16s();
					sp -= 2;
					if (stack[sp+1] < stack[sp+2]) pc = new_pc;
					break;
				case 162 :		// if_icmpge
					new_pc = pc-1;
					new_pc += readOpd16s();
					sp -= 2;
					if (stack[sp+1] >= stack[sp+2]) pc = new_pc;
					break;
				case 163 :		// if_icmpgt
					new_pc = pc-1;
					new_pc += readOpd16s();
					sp -= 2;
					if (stack[sp+1] > stack[sp+2]) pc = new_pc;
					break;
				case 164 :		// if_icmple
					new_pc = pc-1;
					new_pc += readOpd16s();
					sp -= 2;
					if (stack[sp+1] <= stack[sp+2]) pc = new_pc;
					break;
				case 167 :		// goto
					new_pc = pc-1;
					new_pc += readOpd16s();
					pc = new_pc;
					break;
				case 168 :		// jsr
					noim(168);
					break;
				case 169 :		// ret
					noim(169);
					break;
				case 170 :		// tableswitch
					noim(170);
					break;
				case 171 :		// lookupswitch
					noim(171);
					break;
				case 176 :		// areturn
				case 172 :		// ireturn
				case 174 :		// freturn
					ireturn();
					break;
				case 173 :		// lreturn
					lreturn();
					break;
				case 175 :		// dreturn
					lreturn();
					break;
				case 177 :		// return
					vreturn();
					break;
				case 178 :		// getstatic
					getstatic();
					break;
				case 179 :		// putstatic
					putstatic();
					break;
				case 180 :		// getfield
					getfield();
					break;
				case 181 :		// putfield
					putfield();
					break;
				case 182 :		// invokevirtual
					invokevirtual();
					break;
				case 183 :		// invokespecial
					invokespecial();
					break;
				case 184 :		// invokestatic
					invokestatic();
					break;
				case 185 :		// invokeinterface
					invokeinterface();
					break;
				case 186 :		// unused_ba
					noim(186);
					break;
				case 187 :		// new
					jjvmConst(187);

/*	use function in JVM.java
					idx = readOpd16u();
					val = readMem(cp+idx);	// pointer to class struct
					writeMem(heap, val+2);	// pointer to method table on objectref-1
					++heap;
					val = readMem(val);		// instance size
// TODO init object to zero
					stack[++sp] = heap;		// objectref
					heap += val;
System.out.println("new heap: "+heap);
*/
					break;
				case 188 :		// newarray
					readOpd8u();		// ignore typ
					// invoke JVM.f_newarray(int count);
					invoke(jjp+(188<<1));
					/*

					val = stack[sp--];	// count from stack
					writeMem(heap, val);
					++heap;
					stack[++sp] = heap;	// ref to first element
					heap += val;
// System.out.println("newarray heap: "+heap);
 * 
 */
					break;
				case 189 :		// anewarray
					jjvmConst(189);
					break;
				case 190 :		// arraylength
					ref = stack[sp--];	// ref from stack
					if (useHandle) {
						// handle needs indirection
						ref = readMem(ref);
					}
					--ref;				// point to count
					stack[++sp] = readMem(ref);
					break;
				case 191 :		// athrow
					noim(191);
					break;
				case 192 :		// checkcast
					jjvmConst(192);
					break;
				case 193 :		// instanceof
					noim(193);
					break;
				case 194 :		// monitorenter
					sp--;		// we don't use the objref
					intEna = false;
					++moncnt;
					// noim(194);
					break;
				case 195 :		// monitorexit
					sp--;		// we don't use the objref
					--moncnt;
					if (moncnt==0) {
						intEna = true;
					}
					// noim(195);
					break;
				case 196 :		// wide
					noim(196);
					break;
				case 197 :		// multianewarray
					noim(197);
/* 
					stack[++sp] = readOpd8u();		// push dimenensions onto the stack
					// invoke JVM.f_multianewarray(int dim);
					invoke(jjp+(197<<1));
					readOpd16u();	// ignore type information
*/
					break;
				case 200 :		// goto_w
					noim(200);
					break;
				case 201 :		// jsr_w
					noim(201);
					break;
				case 202 :		// breakpoint
					noim(202);
					break;
				case 203 :		// resCB
					noim(203);
					break;
				case 204 :		// resCC
					noim(204);
					break;
				case 205 :		// resCD
					noim(205);
					break;
				case 206 :		// resCE
					noim(206);
					break;
				case 207 :		// resCF
					noim(207);
					break;
				case 208 :		// jopsys_null
					noim(208);
					break;
				case 209 :		// jopsys_rd
					sysRd();
					break;
				case 210 :		// jopsys_wr
					sysWr();
					break;
				case 211 :		// jopsys_rdmem
					ref = stack[sp--];
					stack[++sp] = readMem(ref);
					break;
				case 212 :		// jopsys_wrmem
					ref = stack[sp--];
					val = stack[sp--];
					writeMem(ref, val);
					break;
				case 213 :		// jopsys_rdint
					ref = stack[sp--];
//
//	first variables in jvm.asm
//
//	mp		?		// pointer to method struct
//	cp		?		// pointer to constants
//	heap	?		// start of heap
//
//	jjp		?		// pointer to meth. table of Java JVM functions
//	jjhp	?		// pointer to meth. table of Java JVM help functions
//
//	moncnt	?		// counter for monitor

					if (ref==0) {
						val = mp;
					} else if (ref==1) {
						val = cp;
					} else if (ref==2) {
						val = heap;
					} else if (ref==3) {
						val = jjp;
					} else if (ref==4) {
						val = jjhp;
					} else if (ref==5) {
						val = moncnt;
					} else {
						val = stack[ref];
					}
					stack[++sp] = val;
					break;
				case 214 :		// jopsys_wrint
					ref = stack[sp--];
					val = stack[sp--];
					if (ref==0) {
						mp = val;
					} else if (ref==1) {
						cp = val;
					} else if (ref==2) {
						heap = val;
// System.out.println("jopsys_wrint: heap "+heap);
					} else if (ref==3) {
						jjp = val;
					} else if (ref==4) {
						jjhp = val;
					} else if (ref==5) {
						moncnt = val;
					} else {
						stack[ref] = val;
					}
					break;
				case 215 :		// jopsys_getsp
					val = sp;
					stack[++sp] = val;
					break;
				case 216 :		// jopsys_setsp
					val = stack[sp--];
					sp = val;
					break;
				case 217 :		// jopsys_getvp
					stack[++sp] = vp;
					break;
				case 218 :		// jopsys_setvp
					vp = stack[sp--];
					break;
				case 219 :		// jopsys_int2ext
// public static native void int2extMem(int intAdr, int extAdr, int cnt);
					a = stack[sp--];
					b = stack[sp--];
					if (useHandle) {
						// handle needs indirection
						b = readMem(b);
					}
					c = stack[sp--];
					for(; a>=0; --a) {
						writeMem(b+a, stack[c+a]);
					}
					break;
				case 220 :		// jopsys_ext2int
// public static native void ext2intMem(int extAdr, int intAdr, int cnt);
					a = stack[sp--];
					b = stack[sp--];
					c = stack[sp--];
					if (useHandle) {
						// handle needs indirection
						c = readMem(c);
					}
					for(; a>=0; --a) {
						stack[b+a] = readMem(c+a);
					}
					break;
				case 221 :		// jopsys_nop
					break;
				case 222 :		// jopsys_invoke
					a = stack[sp--];
					invoke(a);
					break;
				case 223 :		// resDF
					noim(223);
					break;
				case 224 :		// resE0
					noim(224);
					break;
				case 225 :		// resE1
					noim(225);
					break;
				case 226 :		// resE2
					noim(226);
					break;
				case 227 :		// resE3
					noim(227);
					break;
				case 228 :		// resE4
					noim(228);
					break;
				case 229 :		// resE5
					noim(229);
					break;
				case 230 :		// resE6
					noim(230);
					break;
				case 231 :		// resE7
					noim(231);
					break;
				case 232 :		// resE8
					noim(232);
					break;
				case 233 :		// resE9
					noim(233);
					break;
				case 234 :		// resEA
					noim(234);
					break;
				case 235 :		// resEB
					noim(235);
					break;
				case 236 :		// resEC
					noim(236);
					break;
				case 237 :		// resED
					noim(237);
					break;
				case 238 :		// resEE
					noim(238);
					break;
				case 239 :		// resEF
					noim(239);
					break;
				case 240 :		// sys_int
					--pc;		// correct wrong increment on jpc
					invoke(jjhp);	// interrupt() is at offset 0
					break;
				case 241 :		// resF1
					noim(241);
					break;
				case 242 :		// resF2
					noim(242);
					break;
				case 243 :		// resF3
					noim(243);
					break;
				case 244 :		// resF4
					noim(244);
					break;
				case 245 :		// resF5
					noim(245);
					break;
				case 246 :		// resF6
					noim(246);
					break;
				case 247 :		// resF7
					noim(247);
					break;
				case 248 :		// resF8
					noim(248);
					break;
				case 249 :		// resF9
					noim(249);
					break;
				case 250 :		// resFA
					noim(250);
					break;
				case 251 :		// resFB
					noim(251);
					break;
				case 252 :		// resFC
					noim(252);
					break;
				case 253 :		// resFD
					noim(253);
					break;
				case 254 :		// sys_noim
					noim(254);
					break;
				case 255 :		// sys_init
					noim(255);
					break;

				default:
					noim(instr);

			}


		}

	}

	void stat() {

System.out.println();
/*
int sum = 0;
int sumcnt = 0;
for (int i=0; i<256; ++i) {
	if (bcStat[i] > 0) {
		System.out.println(bcStat[i]+"\t"+(bcStat[i]*JopInstr.cnt(i))+"\t"+JopInstr.name(i));
		sum += bcStat[i];
		sumcnt = bcStat[i]*JopInstr.cnt(i);
	}
}
System.out.println();
System.out.println(sum+" instructions, "+sumcnt+" cycles, "+instrBytesCnt+" bytes");
*/
		System.out.println(maxSp+" maximum sp");
		System.out.println(heap+" heap");
		System.out.println();
		System.out.println(instrCnt+" Instructions executed");
		int insByte = cache.instrBytes();
		System.out.println(insByte+" Instructions bytes");
		System.out.println(((float) insByte/instrCnt)+" average Instruction length");
		System.out.println("memory word: "+rdMemCnt+" load "+wrMemCnt+" store");
		System.out.println("memory word per instruction: "+
			((float) rdMemCnt/instrCnt)+" load "+
			((float) wrMemCnt/instrCnt)+" store");
		System.out.println();


	}

	public static void main(String args[]) {

		JopSim js = null;
		if (args.length==1) {
			js = new JopSim(args[0]);
		} else if (args.length==2) {
			js = new JopSim(args[0], Integer.parseInt(args[1]));
		} else {
			System.out.println("usage: java JopSim file.bin [max instr]");
			System.exit(-1);
		}

		log = System.getProperty("log", "false").equals("true");
		useHandle = System.getProperty("handle", "false").equals("true");

		for (int i=0; i<js.cache.cnt(); ++i) {
			js.cache.use(i);
			js.start();
			js.interpret();
			if (i==0) js.stat();
			js.cache.stat();
		}
	}
}
