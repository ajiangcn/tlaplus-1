// Copyright (c) 2003 Compaq Corporation.  All rights reserved.

/***************************************************************************
*                                                                          *
* This program converts TLA+ specifications from ASCII to Unicode          * 
* representation and vice-versa.                                           *
*                                                                          *
* BuiltInSymbols.Initialize                                                *
*    Initializes tables containing information about TLA's built-in        *
*    symbols.                                                              *
*                                                                          *
* TokenizeSpec.Tokenize                                                    *
*    Reads the input file and turns it into an array of array of Token     *
*    objects `spec', where spec[i][j] is item j on line i.  Each line of   *
*    a comment is a single token.  (In the Java program, all numbering     *
*    starts from 0.  The error messages translate into the numbering       *
*    system used by most humans, in which the first object is object       *
*    number 1.)  This method does not detect PF_STEP tokens such           *
*    as "<2>3a.".                                                          *
*                                                                          *
* TokenizeSpec.FindPfStepTokens                                            *
*    Converts a sequence of tokens that represent a proof step             *
*    number into a single PF_STEP token.                                   *
*                                                                          *
* CommentToken.ProcessComments                                             *
*    Determines which comment tokens are part of a single multi-line       *
*    comment, and indicates this by setting the subtype field of           *
*    comment tokens.                                                       *
*                                                                          *
* FormatComments.Initialize                                                *
*    Initializes tables used for formatting comments.  One table           *
*    contains common English words, which are read from the file           *
*    Parameters.WordFile                                                   *
*                                                                          *
* FindAlignments.FindAlignment                                             *
*    Determines what tokens should be aligned with what other tokens.      *
*    This is indicated by setting the tokens' belowAlign and aboveAlign    *
*    fields.  It also sets the isAlignmentPoint flag for each token        *
*    whose left-hand edge is a point used by some other token for its      *
*    alignment.                                                            *
*                                                                          *
*                                                                          *                   
* ------------------------------------------------------------------------ *
*                                                                          *
* Bug: When a line ends like so `\* abc (* def *)`, the `def` token is     *
*      lost. (Bug in TokenizeSpec)                                         *
*                                                                          *
***************************************************************************/
package unicasc;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import tla2tex.BuiltInSymbols;
import tla2tex.CharReader;
import tla2tex.CommentToken;
import tla2tex.Debug;
import tla2tex.FileCharReader;
import tla2tex.FindAlignments;
import tla2tex.OutputFileWriter;
import tla2tex.TLA2TexException;
import tla2tex.Token;
import tla2tex.TokenizeSpec;
import util.ToolIO;

public class TLAUnicode {
	static final String APP = "unicasc.TLAUnicode";
	static final String VERSION = APP + " Version 1.0";
	
	static final String USAGE = "USAGE: java " + APP + " OP input.tla [output.tla]"
			+ "\nOP is -u2a | -a2u to convert from ASCII to Unicode or vice-versa, respectively."
			+ "\nIf the output file isn't specified, the conversion is printed to the standard output.";
	
  	private static boolean debug = false ; // True if the -debug option is chosen.
  	private static boolean toU; // True for ASCII -> Unicode, false for Unicode -> ASCII
  	private static String inputFile = "" ; // The name of the input file
  	private static String outputFile = "" ; // The name of the output file
  
	public static void main(String[] args) {
		// ToolIO.out.println(version);
		getArguments(args); // Get the command-line arguments.

		BuiltInSymbols.Initialize(); // Initialize class BuiltInSymbols

		// Read and tokenize the spec.
		final CharReader testlr = new FileCharReader(inputFile);
		final Token[][] spec = TokenizeSpec.Tokenize(testlr, TokenizeSpec.MODULE);

		Token.FindPfStepTokens(spec); // Convert proof-step numbers to PF_STEP tokens.
		// Debug.print2DArray(spec, "tok");

		CommentToken.ProcessComments(spec); // Process the comment tokens.
		// Debug.print2DArray(spec, "com");

		FindAlignments.FindAlignments(spec); // Add the alignment pointers to spec.
		// Debug.print2DArray(spec, "align");
		
		convert(spec, toU, // Write output
				outputFile != null 
					? new OutputFileWriter(outputFile)
					: new OutputFileWriter(System.out, "STDOUT"));
	}

	public static void convert(Token[][] spec, boolean toU, OutputFileWriter writer) {
		// This method performs the actual conversion
		
		List<CommentToken> leftComments = null;
		
		for (int line = 0; line < spec.length; line++) {
			final StringBuilder out = new StringBuilder();
			leftComments = new ArrayList<>(); // left comments that we may need to move to the end of the line 
			boolean onlyComments = true; // we've only encountered comment tokens on this line so far
			boolean keepLeftComments = false;
			
			for (int item = 0; item < spec[line].length; item++) {
				final Token tok = spec[line][item];
//				System.out.println(tok);
//				if (item == spec[line].length - 1)
//					System.out.println("$$$$$$$$$");
				
				// if line ends with an open comment or a line comment and we have left comments to move, 
			    // we wait to output the comment.
				if (keepLeftComments && item == spec[line].length - 1 && tok.type == Token.COMMENT) {
					final CommentToken ctok = (CommentToken) tok;
					// append skipped last comment token
					if (ctok.rsubtype == CommentToken.BEGIN_OVERRUN || ctok.rsubtype == CommentToken.LINE)
						continue;
				}
				
				//---- Align token ----
				
				int space = -1; // how much space to leave before the token
				if (tok.aboveAlign.line != -1) {
					// if aligned to a token above -- try to keep alignment
					final Token align = tok.aboveAlign.toToken(spec);
					if (align.column == tok.column && align.outcolumn >= 0) {
						final int column = out.length();
						space = align.outcolumn - column;
						
						// If we're the first non-comment token and we can't align
						// we move all left comments to the end of the line. 
						// We drop them from the line, and keep them in leftComments
						if (space < 0 && onlyComments && tok.type != Token.COMMENT) {
							Debug.Assert(toU); // can't happen in U -> A
							
							out.delete(0, out.length()); // reset line
							space = align.outcolumn;
							
							keepLeftComments = true;
							for (CommentToken ctok : leftComments)
								ctok.outcolumn = -1;
							
							if (!leftComments.isEmpty() && leftComments.get(0).rsubtype == CommentToken.END_OVERRUN) {
								out.append("*)");
								space -= 2;
							}
						}
					}
				}
				if (space < 0) // otherwise, keep original spacing
					space = tok.column - (item > 0 ? spec[line][item - 1].column + spec[line][item - 1].getWidth() : 0);
				
				Debug.Assert(space >= 0, tok + (item > 0 ? " :: " + spec[line][item - 1] : ""));
				appendSpaces(out, space);
				
				if (tok.type != Token.COMMENT) {
					onlyComments = false;
					if (!keepLeftComments)
						leftComments = null;
				}
				
				tok.outcolumn = out.length();
				Debug.Assert(toU 
						? tok.outcolumn <= tok.column
						: tok.outcolumn >= tok.column, 
					tok.toString());

				//----- Output token ----
				
				switch (tok.type) {
				case Token.BUILTIN: {
					// Here we actually convert the symbol
					String alt = toU ? Unicode.a2u(tok.string) : Unicode.u2a(tok.string);
					out.append(alt != null ? alt : tok.string);
					break;
				}
				case Token.NUMBER:
				case Token.IDENT:
				case Token.PCAL_LABEL:
				case Token.DASHES:
				case Token.END_MODULE:
				case Token.PROLOG:
				case Token.EPILOG:
				case Token.PF_STEP:
					out.append(tok.string);
					break;
				case Token.STRING:
					out.append("\"" + tok.string + "\"");
					break;

				case Token.COMMENT:
					final CommentToken ctok = (CommentToken) tok; // the current comment token
					if (onlyComments && leftComments != null)
						leftComments.add(ctok);
					appendCommentToken(out, ctok);
					break;

				default:
					Debug.ReportBug("Bad token type found.");
					break;
				}
			}
			
			if (keepLeftComments) { // we have comments to move to the end of the line
				for (CommentToken ctok : leftComments)
					out.append(" (*" + ctok.string + "*)");
				final Token last = spec[line][spec[line].length-1]; 
				if (last.type == Token.COMMENT) {
					final CommentToken ctok = (CommentToken) last;
					// append skipped last comment token
					if (ctok.rsubtype == CommentToken.BEGIN_OVERRUN || ctok.rsubtype == CommentToken.LINE) {
						out.append(" ");
						appendCommentToken(out, ctok);
					}
				}
			}
			
			writer.putLine(out.toString());
		}
		writer.close();
	}
    
	private static void appendCommentToken(StringBuilder out, CommentToken ctok) {
		final String commentString = ctok.string;
		switch (ctok.rsubtype) {
		case CommentToken.NORMAL:
			out.append("(*" + commentString + "*)");
			break;
		case CommentToken.LINE:
			out.append("\\*" + commentString);
			break;
		case CommentToken.BEGIN_OVERRUN:
			if (ctok.getWidth() > 0)
				out.append("(*" + commentString);
			break;
		case CommentToken.END_OVERRUN:
			out.append(commentString + "*)");
			break;
		case CommentToken.OVERRUN:
			out.append(commentString);
			break;
		default:
			Debug.ReportBug("Bad CommentToken subtype found.");
		}
	}
	
	private static void appendSpaces(StringBuilder sb, int n) {
		for (int i = 0; i < n; i++)
			sb.append(' ');
	}
	
	private static boolean isInPcal(int line, int item) {
		return TokenizeSpec.hasPcal 
				&& line >= TokenizeSpec.pcalStart.line && item >= TokenizeSpec.pcalStart.item
				&& (line < TokenizeSpec.pcalEnd.line 
						|| (line == TokenizeSpec.pcalEnd.line && item < TokenizeSpec.pcalStart.item));
	}
    
	// ----------- COMMAND LINE PARSING ---------------------------------------
	
	private static void getArguments(String[] args) {
		 // Get the command-line arguments and set the appropriate static fields.
		
		if (args.length == 0)
			commandLineError("No arguments specified");
		
		int argi = 0; // The index of the command line argument being processed.
		boolean hasOp = false; // Whether or not -a2u or -u2a has been encountered.
		loop:
		while (argi < args.length) {
			// Process all the arguments, except for the last (unless it's a "-" argument).
			final String option = args[argi];
			switch(option) {
			case "-help":
				System.err.println(USAGE);
				System.exit(0);
				break;
			case "-debug":
				debug = true;
				break;
			case "-a2u":
				if (hasOp)
					commandLineError("Only one of -a2u or -u2a must be specified");
				hasOp = true;
				toU = true;
				break;
			case "-u2a":
				if (hasOp)
					commandLineError("Only one of -a2u or -u2a must be specified");
				hasOp = true;
				toU = false;
				break;
			default:
				if (option.startsWith("-"))
					commandLineError("Unsupported option " + option);
				break loop;
			}
			argi++;
		}
		if (!hasOp)
			commandLineError("One of -a2u or -u2a must be specified");

		// Input file
		if (argi >= args.length)
			commandLineError("Input file not specified");
		inputFile = args[argi];
		
		argi++;
		
		// Output file
		if (argi >= args.length)
			outputFile = null;
		else {
			outputFile = args[argi];
			// Report an error if inputFile = outFile.
			if (Objects.equals(Paths.get(inputFile).normalize().toAbsolutePath(),
					Paths.get(outputFile).normalize().toAbsolutePath()))
				commandLineError("Output file is the same as the tla input file."
						+ " This would overwrite your input file, so I won't do it");
		}
	}

	private static void commandLineError(String msg) {
		ToolIO.out.println(APP + " command-line error: " + msg + ".");
		ToolIO.out.println(USAGE);
		throw new TLA2TexException(
				APP + " command-line error: " + msg + "." + "Use -help option for more information.");
	}
}