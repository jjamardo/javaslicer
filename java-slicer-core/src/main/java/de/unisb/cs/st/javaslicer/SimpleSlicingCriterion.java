package de.unisb.cs.st.javaslicer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.unisb.cs.st.javaslicer.tracer.classRepresentation.Instruction;
import de.unisb.cs.st.javaslicer.tracer.classRepresentation.LocalVariable;
import de.unisb.cs.st.javaslicer.tracer.classRepresentation.ReadClass;
import de.unisb.cs.st.javaslicer.tracer.classRepresentation.ReadMethod;
import de.unisb.cs.st.javaslicer.tracer.classRepresentation.instructions.AbstractInstruction;

public class SimpleSlicingCriterion implements SlicingCriterion {

    public static class LocalVariableCriterion implements CriterionVariable {

        private final ReadMethod method;
        private final int varIndex;

        public LocalVariableCriterion(final ReadMethod method, final int varIndex) {
            this.method = method;
            this.varIndex = varIndex;
        }

        @Override
        public Variable instantiate(final ExecutionFrame execFrame) {
            return execFrame.getLocalVariable(this.varIndex);
        }

        @Override
        public String toString() {
            final List<LocalVariable> locals = this.method.getLocalVariables();
            for (final LocalVariable loc: locals)
                if (loc.getIndex() == this.varIndex)
                    return loc.getName();
            return "<unknown>";
        }

    }

    public static interface CriterionVariable {

        Variable instantiate(ExecutionFrame execFrame);

    }

    public class Instance implements SlicingCriterion.Instance {

        private long seenOccurences = 0;
        private boolean beingInRun = false;

        @Override
        public Collection<Variable> getInterestingVariables(final ExecutionFrame execFrame) {
            final List<Variable> varList = new ArrayList<Variable>(SimpleSlicingCriterion.this.variables.size());
            for (final CriterionVariable var: SimpleSlicingCriterion.this.variables)
                varList.add(var.instantiate(execFrame));

            return varList;
        }

        @Override
        public boolean matches(final Instruction.Instance instructionInstance) {
            if ((SimpleSlicingCriterion.this.occurence != null &&
                    this.seenOccurences == SimpleSlicingCriterion.this.occurence)
                || instructionInstance.getMethod() != SimpleSlicingCriterion.this.method
                || instructionInstance.getLineNumber() != SimpleSlicingCriterion.this.lineNumber) {
                if (this.beingInRun)
                    this.beingInRun = false;
                return false;
            }

            if (this.beingInRun)
                return true;
            this.beingInRun = SimpleSlicingCriterion.this.occurence == null ||
                ++this.seenOccurences == SimpleSlicingCriterion.this.occurence;
            return this.beingInRun;
        }

        @Override
        public String toString() {
            return SimpleSlicingCriterion.this.toString();
        }

    }

    protected final ReadMethod method;
    protected final Integer lineNumber;
    protected final Long occurence;
    protected final Collection<CriterionVariable> variables;

    public SimpleSlicingCriterion(final ReadMethod method, final Integer lineNumber,
            final Long occurence, final Collection<CriterionVariable> variables) {
        this.method = method;
        this.lineNumber = lineNumber;
        this.occurence = occurence;
        this.variables = variables;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append(this.method.getReadClass().getName()).append('.').append(this.method.getName());
        if (this.lineNumber != null)
            sb.append(':').append(this.lineNumber.intValue());
        if (this.occurence != null)
            sb.append('(').append(this.occurence.longValue()).append(')');
        if (this.variables != null && !this.variables.isEmpty()) {
            final Iterator<CriterionVariable> it = this.variables.iterator();
            sb.append(":{").append(it.next());
            while (it.hasNext())
                sb.append(',').append(it.next());
            sb.append('}');
        }
        return sb.toString();
    }

    private static final Pattern slicingCriterionPattern = Pattern.compile(
            "([^:{}]+)\\.([^:{}]+?)(?::(\\d+))?(?:\\((\\d+)\\))?(?::\\{(.*?)\\})?");

    public static SlicingCriterion parse(final String string, final List<ReadClass> readClasses) throws IllegalParameterException {
        final Matcher matcher = slicingCriterionPattern.matcher(string);
        if (!matcher.matches())
            throw new IllegalParameterException("Slicing could not be parsed: " + string);

        final String className = matcher.group(1);
        final String methodName = matcher.group(2);
        final String lineNumberStr = matcher.group(3);
        final String occurenceStr = matcher.group(4);
        final String variableDef = matcher.group(5);
        Integer lineNumber = null;
        if (lineNumberStr != null)
            try {
                lineNumber = Integer.valueOf(lineNumberStr);
            } catch (final NumberFormatException e) {
                throw new IllegalParameterException("Expected line number, found '"+lineNumberStr+"'");
            }
        Long occurence = null;
        if (occurenceStr != null)
            try {
                occurence = Long.valueOf(occurenceStr);
            } catch (final NumberFormatException e) {
                throw new IllegalParameterException("Expected occurrence number, found '"+occurenceStr+"'");
            }

        final ReadMethod method = findMethod(readClasses, className, methodName, lineNumber);
        assert method != null;

        final Collection<CriterionVariable> variables = parseVariables(method, variableDef);

        return new SimpleSlicingCriterion(method, lineNumber, occurence, variables);
    }

    private static ReadMethod findMethod(final List<ReadClass> readClasses, final String className, final String methodName,
            final Integer lineNumber) throws IllegalParameterException {
        // binary search
        int left = 0;
        int right = readClasses.size();
        int mid;

        while ((mid = (left + right) / 2) != left) {
            final ReadClass midVal = readClasses.get(mid);
            if (midVal.getName().compareTo(className) <= 0)
                left = mid;
            else
                right = mid;
        }

        final ReadClass foundClass = readClasses.get(mid);
        if (!className.equals(foundClass.getName()))
            throw new IllegalParameterException("Class does not exist: " + className);

        final ArrayList<ReadMethod> methods = foundClass.getMethods();
        left = 0;
        right = methods.size();

        while ((mid = (left + right) / 2) != left) {
            final ReadMethod midVal = methods.get(mid);
            if (midVal.getName().compareTo(methodName) <= 0)
                left = mid;
            else
                right = mid;
        }

        final ReadMethod foundMethod = methods.get(mid);
        if (foundMethod.getName().equals(methodName)) {
            for (final ReadMethod m = methods.get(mid); mid < methods.size() && m.getName().equals(methodName); ++mid) {
                for (final AbstractInstruction instr: m.getInstructions()) {
                    if (instr.getLineNumber() == lineNumber)
                        return m;
                }
            }
            throw new IllegalParameterException("Found no method with name " + methodName +
                    " in class " + className + " which contains line number " + lineNumber);
        }
        throw new IllegalParameterException("Method does not exist: " + className + "." + methodName);

    }

    private static final Pattern variableDefinitionPattern = Pattern.compile(
        "\\s*(?:([a-zA-Z_][a-zA-Z0-9_\\-]*)" // local variable
            + ")\\s*");

    private static Collection<CriterionVariable> parseVariables(final ReadMethod method, final String variables) throws IllegalParameterException {
        if (variables == null)
            return Collections.emptySet();
        final String[] parts = variables.split(",");
        final List<CriterionVariable> varList = new ArrayList<CriterionVariable>();
        for (final String part: parts) {
            final Matcher matcher = variableDefinitionPattern.matcher(part);
            if (!matcher.matches())
                throw new IllegalParameterException("Illegal variable definition: " + part);
            final String localVarStr = matcher.group(1);
            if (localVarStr == null)
                throw new IllegalParameterException("Illegal variable definition: " + part);

            int localVarIndex = -1;
            for (final LocalVariable var: method.getLocalVariables()) {
                if (localVarStr.equals(var.getName())) {
                    localVarIndex = var.getIndex();
                    break;
                }
            }
            if (localVarIndex == -1)
                throw new IllegalParameterException("Local variable '"+localVarStr+"' not found in method "
                        + method.getReadClass().getName()+"."+method.getName());
            varList.add(new LocalVariableCriterion(method, localVarIndex));
        }

        return varList;
    }

    @Override
    public SlicingCriterion.Instance getInstance() {
        return new Instance();
    }

}