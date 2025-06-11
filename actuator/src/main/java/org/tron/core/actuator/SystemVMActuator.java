package org.tron.core.actuator;

import static org.apache.commons.lang3.ArrayUtils.getLength;
import static org.apache.commons.lang3.ArrayUtils.isNotEmpty;

import com.google.protobuf.ByteString;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.util.encoders.Hex;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.InternalTransaction;
import org.tron.common.runtime.InternalTransaction.ExecutorType;
import org.tron.common.runtime.InternalTransaction.TrxType;
import org.tron.common.runtime.ProgramResult;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.StorageUtils;
import org.tron.common.utils.StringUtil;
import org.tron.common.utils.WalletUtil;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.db.TransactionContext;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.utils.TransactionUtil;
import org.tron.core.vm.EnergyCost;
import org.tron.core.vm.Op;
import org.tron.core.vm.OperationRegistry;
import org.tron.core.vm.VM;
import org.tron.core.vm.VMConstant;
import org.tron.core.vm.VMUtils;
import org.tron.core.vm.config.ConfigLoader;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.Program.JVMStackOverFlowException;
import org.tron.core.vm.program.Program.OutOfTimeException;
import org.tron.core.vm.program.Program.TransferException;
import org.tron.core.vm.program.ProgramPrecompile;
import org.tron.core.vm.program.invoke.ProgramInvoke;
import org.tron.core.vm.program.invoke.ProgramInvokeFactory;
import org.tron.core.vm.repository.Repository;
import org.tron.core.vm.repository.RepositoryImpl;
import org.tron.core.vm.utils.MUtil;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.Transaction.Result.contractResult;
import org.tron.protos.contract.SmartContractOuterClass.CreateSmartContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

@Slf4j(topic = "VM")
public class SystemVMActuator implements Actuator2 {

  /* tx and block info */
  private Transaction trx;
  private BlockCapsule blockCap;

  /* tvm execution context */
  private Repository rootRepository;
  private Program program;
  private InternalTransaction rootInternalTx;

  @Getter
  @Setter
  private TrxType trxType;

  private long maxEnergyLimit;

  public SystemVMActuator() {
    this.maxEnergyLimit = CommonParameter.getInstance().maxEnergyLimitForConstant;
  }

  @Override
  public void validate(Object object) throws ContractValidateException {

    TransactionContext context = (TransactionContext) object;
    if (Objects.isNull(context)) {
      throw new RuntimeException("TransactionContext is null");
    }

    // Load Config
    ConfigLoader.load(context.getStoreFactory());
    // Warm up registry class
    OperationRegistry.init();
    trx = context.getTrxCap().getInstance();
    blockCap = context.getBlockCap();
    //Route Type
    ContractType contractType = this.trx.getRawData().getContract(0).getType();
    //Prepare Repository
    rootRepository = RepositoryImpl.createRoot(context.getStoreFactory());

    if (Objects.isNull(blockCap)) {
      this.blockCap = new BlockCapsule(Block.newBuilder().build());
    }
    //set executorType type
//    if (Objects.nonNull(blockCap)) {
//      this.executorType = ExecutorType.ET_NORMAL_TYPE;
//    } else {
//      this.blockCap = new BlockCapsule(Block.newBuilder().build());
//      this.executorType = ExecutorType.ET_PRE_TYPE;
//    }

    switch (contractType.getNumber()) {
      case ContractType.TriggerSmartContract_VALUE:
        trxType = TrxType.TRX_CONTRACT_CALL_TYPE;
        call();
        break;
      case ContractType.CreateSmartContract_VALUE:
        trxType = TrxType.TRX_CONTRACT_CREATION_TYPE;
        create();
        break;
      default:
        throw new ContractValidateException("Unknown contract type");
    }
  }

  @Override
  public void execute(Object object) throws ContractExeException {
    TransactionContext context = (TransactionContext) object;
    if (Objects.isNull(context)) {
      throw new RuntimeException("TransactionContext is null");
    }

    ProgramResult result = context.getProgramResult();
    try {
      if (program != null) {
        if (null != blockCap && blockCap.generatedByMyself && blockCap.hasWitnessSignature()
            && null != TransactionUtil.getContractRet(trx)
            && contractResult.OUT_OF_TIME == TransactionUtil.getContractRet(trx)) {
          result = program.getResult();
          program.spendAllEnergy();

          OutOfTimeException e = Program.Exception.alreadyTimeOut();
          result.setRuntimeError(e.getMessage());
          result.setException(e);
          throw e;
        }

        VM.play(program, OperationRegistry.getTable());
        result = program.getResult();

        if (VMConfig.allowEnergyAdjustment()) {
          // If the last op consumed too much execution time, the CPU time limit for the whole tx can be exceeded.
          // This is not fair for other txs in the same block.
          // So when allowFairEnergyAdjustment is on, the CPU time limit will be checked at the end of tx execution.
          program.checkCPUTimeLimit(Op.getNameOf(program.getLastOp()) + "(TX_LAST_OP)");
        }

        if (TrxType.TRX_CONTRACT_CREATION_TYPE == trxType && !result.isRevert()) {
          byte[] code = program.getResult().getHReturn();
          if (code.length != 0 && VMConfig.allowTvmLondon() && code[0] == (byte) 0xEF) {
            if (null == result.getException()) {
              result.setException(Program.Exception.invalidCodeException());
            }
          }
          long saveCodeEnergy = (long) getLength(code) * EnergyCost.getCreateData();
//          long afterSpend = program.getEnergyLimitLeft().longValue() - saveCodeEnergy;
          result.spendEnergy(saveCodeEnergy);
          if (VMConfig.allowTvmConstantinople()) {
            rootRepository.saveCode(program.getContractAddress().getNoLeadZeroesData(), code);
          }
        }

        if (result.getException() != null || result.isRevert()) {
          result.getDeleteAccounts().clear();
          result.getLogInfoList().clear();
          //result.resetFutureRefund();
          result.rejectInternalTransactions();

          if (result.getException() != null) {
            if (!(result.getException() instanceof TransferException)) {
              program.spendAllEnergy();
            }
            result.setRuntimeError(result.getException().getMessage());
            throw result.getException();
          } else {
            result.setRuntimeError("REVERT opcode executed");
          }
        } else {
          rootRepository.commit();
        }
      } else {
        rootRepository.commit();
      }
      for (DataWord account : result.getDeleteAccounts()) {
        RepositoryImpl.removeLruCache(account.toTronAddress());
      }
    } catch (JVMStackOverFlowException e) {
      program.spendAllEnergy();
      result = program.getResult();
      result.setException(e);
      result.rejectInternalTransactions();
      result.setRuntimeError(result.getException().getMessage());
      logger.info("JVMStackOverFlowException: {}", result.getException().getMessage());
    } catch (OutOfTimeException e) {
      program.spendAllEnergy();
      result = program.getResult();
      result.setException(e);
      result.rejectInternalTransactions();
      result.setRuntimeError(result.getException().getMessage());
      logger.info("timeout: {}", result.getException().getMessage());
    } catch (Throwable e) {
      if (!(e instanceof TransferException)) {
        program.spendAllEnergy();
      }
      result = program.getResult();
      result.rejectInternalTransactions();
      if (Objects.isNull(result.getException())) {
        logger.error(e.getMessage(), e);
        result.setException(new RuntimeException("Unknown Throwable"));
      }
      if (StringUtils.isEmpty(result.getRuntimeError())) {
        result.setRuntimeError(result.getException().getMessage());
      }
      logger.info("runtime result is :{}", result.getException().getMessage());
    }
    //use program returned fill context
    context.setProgramResult(result);

    if (VMConfig.vmTrace() && program != null) {
      String traceContent = program.getTrace()
          .result(result.getHReturn())
          .error(result.getException())
          .toString();

      if (VMConfig.vmTraceCompressed()) {
        traceContent = VMUtils.zipAndEncode(traceContent);
      }

      String txHash = Hex.toHexString(rootInternalTx.getHash());
      VMUtils.saveProgramTraceFile(txHash, traceContent);
    }

  }

  private void create()
      throws ContractValidateException {
    if (!rootRepository.getDynamicPropertiesStore().supportVM()) {
      throw new ContractValidateException("vm work is off, need to be opened by the committee");
    }

    CreateSmartContract contract = ContractCapsule.getSmartContractFromTransaction(trx);
    if (contract == null) {
      throw new ContractValidateException("Cannot get CreateSmartContract from transaction");
    }
    SmartContract newSmartContract;
    if (VMConfig.allowTvmCompatibleEvm()) {
      newSmartContract = contract.getNewContract().toBuilder().setVersion(1).build();
    } else {
      newSmartContract = contract.getNewContract().toBuilder().clearVersion().build();
    }
    if (!contract.getOwnerAddress().equals(newSmartContract.getOriginAddress())) {
      logger.info("OwnerAddress not equals OriginAddress");
      throw new ContractValidateException("OwnerAddress is not equals OriginAddress");
    }

    byte[] contractName = newSmartContract.getName().getBytes();

    if (contractName.length > VMConstant.CONTRACT_NAME_LENGTH) {
      throw new ContractValidateException("contractName's length cannot be greater than 32");
    }

    long percent = contract.getNewContract().getConsumeUserResourcePercent();
    if (percent < 0 || percent > VMConstant.ONE_HUNDRED) {
      throw new ContractValidateException("percent must be >= 0 and <= 100");
    }

    byte[] contractAddress = WalletUtil.generateContractAddress(trx);
    // insure the new contract address haven't exist
    if (rootRepository.getAccount(contractAddress) != null) {
      throw new ContractValidateException(
          "Trying to create a contract with existing contract address: " + StringUtil
              .encode58Check(contractAddress));
    }

    newSmartContract = newSmartContract.toBuilder()
        .setContractAddress(ByteString.copyFrom(contractAddress)).build();
    long callValue = newSmartContract.getCallValue();
    long tokenValue = 0;
    long tokenId = 0;
    if (VMConfig.allowTvmTransferTrc10()) {
      tokenValue = contract.getCallTokenValue();
      tokenId = contract.getTokenId();
    }
    byte[] callerAddress = contract.getOwnerAddress().toByteArray();
    // create vm to constructor smart contract
    try {
      long feeLimit = trx.getRawData().getFeeLimit();
      if (feeLimit < 0 || feeLimit > rootRepository.getDynamicPropertiesStore().getMaxFeeLimit()) {
        logger.info("invalid feeLimit {}", feeLimit);
        throw new ContractValidateException("feeLimit must be >= 0 and <= "
            + rootRepository.getDynamicPropertiesStore().getMaxFeeLimit());
      }
      AccountCapsule creator = rootRepository
          .getAccount(newSmartContract.getOriginAddress().toByteArray());

      checkTokenValueAndId(tokenValue, tokenId);

      byte[] ops = newSmartContract.getBytecode().toByteArray();
      rootInternalTx = new InternalTransaction(trx, trxType);

      long maxCpuTimeOfOneTx = rootRepository.getDynamicPropertiesStore()
          .getMaxCpuTimeOfOneTx() * VMConstant.ONE_THOUSAND;
      long thisTxCPULimitInUs = (long) (maxCpuTimeOfOneTx * getCpuLimitInUsRatio());
      long vmStartInUs = System.nanoTime() / VMConstant.ONE_THOUSAND;
      long vmShouldEndInUs = vmStartInUs + thisTxCPULimitInUs;
      ProgramInvoke programInvoke = ProgramInvokeFactory
          .createProgramInvoke(trxType, ExecutorType.ET_NORMAL_TYPE, trx,
              tokenValue, tokenId, blockCap.getInstance(), rootRepository, vmStartInUs,
              vmShouldEndInUs, maxEnergyLimit);
      this.program = new Program(ops, contractAddress, programInvoke, rootInternalTx);
      if (VMConfig.allowTvmCompatibleEvm()) {
        this.program.setContractVersion(1);
      }
      byte[] txId = TransactionUtil.getTransactionId(trx).getBytes();
      this.program.setRootTransactionId(txId);
    } catch (Exception e) {
      logger.info(e.getMessage());
      throw new ContractValidateException(e.getMessage());
    }
    program.getResult().setContractAddress(contractAddress);

    rootRepository.createAccount(contractAddress, newSmartContract.getName(),
        Protocol.AccountType.Contract);

    rootRepository.createContract(contractAddress, new ContractCapsule(newSmartContract));
    byte[] code = newSmartContract.getBytecode().toByteArray();
    if (!VMConfig.allowTvmConstantinople()) {
      rootRepository.saveCode(contractAddress, ProgramPrecompile.getCode(code));
    }
    // transfer from callerAddress to contractAddress according to callValue
    if (callValue > 0) {
      MUtil.transfer(rootRepository, callerAddress, contractAddress, callValue);
    }
    if (VMConfig.allowTvmTransferTrc10() && tokenValue > 0) {
      MUtil.transferToken(rootRepository, callerAddress, contractAddress, String.valueOf(tokenId),
          tokenValue);
    }

  }

  /**
   * **
   */

  private void call() throws ContractValidateException {

    if (!rootRepository.getDynamicPropertiesStore().supportVM()) {
      logger.info("vm work is off, need to be opened by the committee");
      throw new ContractValidateException("VM work is off, need to be opened by the committee");
    }

    TriggerSmartContract contract = ContractCapsule.getTriggerContractFromTransaction(trx);
    if (contract == null) {
      return;
    }

    if (contract.getContractAddress() == null) {
      throw new ContractValidateException("Cannot get contract address from TriggerContract");
    }

    byte[] contractAddress = contract.getContractAddress().toByteArray();

    ContractCapsule deployedContract = rootRepository.getContract(contractAddress);
    // todo
    if (null == deployedContract) {
      logger.info("No contract or not a smart contract");
      throw new ContractValidateException("No contract or not a smart contract");
    }

    long callValue = contract.getCallValue();
    long tokenValue = 0;
    long tokenId = 0;
    if (VMConfig.allowTvmTransferTrc10()) {
      tokenValue = contract.getCallTokenValue();
      tokenId = contract.getTokenId();
    }

    if (StorageUtils.getEnergyLimitHardFork()) {
      if (callValue < 0) {
        throw new ContractValidateException("callValue must be >= 0");
      }
      if (tokenValue < 0) {
        throw new ContractValidateException("tokenValue must be >= 0");
      }
    }

    byte[] callerAddress = contract.getOwnerAddress().toByteArray();
    checkTokenValueAndId(tokenValue, tokenId);

    byte[] code = rootRepository.getCode(contractAddress);
    if (isNotEmpty(code)) {
      long feeLimit = trx.getRawData().getFeeLimit();
      if (feeLimit < 0 || feeLimit > rootRepository.getDynamicPropertiesStore().getMaxFeeLimit()) {
        logger.info("invalid feeLimit {}", feeLimit);
        throw new ContractValidateException("feeLimit must be >= 0 and <= "
            + rootRepository.getDynamicPropertiesStore().getMaxFeeLimit());
      }
      AccountCapsule caller = rootRepository.getAccount(callerAddress);

      long maxCpuTimeOfOneTx = rootRepository.getDynamicPropertiesStore()
          .getMaxCpuTimeOfOneTx() * VMConstant.ONE_THOUSAND;
      long thisTxCPULimitInUs =
          (long) (maxCpuTimeOfOneTx * getCpuLimitInUsRatio());
      long vmStartInUs = System.nanoTime() / VMConstant.ONE_THOUSAND;
      long vmShouldEndInUs = vmStartInUs + thisTxCPULimitInUs;
      ProgramInvoke programInvoke = ProgramInvokeFactory
          .createProgramInvoke(trxType, ExecutorType.ET_NORMAL_TYPE, trx,
              tokenValue, tokenId, blockCap.getInstance(), rootRepository, vmStartInUs,
              vmShouldEndInUs, maxEnergyLimit);
      rootInternalTx = new InternalTransaction(trx, trxType);
      this.program = new Program(code, contractAddress, programInvoke, rootInternalTx);
      if (VMConfig.allowTvmCompatibleEvm()) {
        this.program.setContractVersion(deployedContract.getContractVersion());
      }
      byte[] txId = TransactionUtil.getTransactionId(trx).getBytes();
      this.program.setRootTransactionId(txId);
    }

    program.getResult().setContractAddress(contractAddress);
    //transfer from callerAddress to targetAddress according to callValue

    if (callValue > 0) {
      MUtil.transfer(rootRepository, callerAddress, contractAddress, callValue);
    }
    if (VMConfig.allowTvmTransferTrc10() && tokenValue > 0) {
      MUtil.transferToken(rootRepository, callerAddress, contractAddress, String.valueOf(tokenId),
          tokenValue);
    }

  }

  public void checkTokenValueAndId(long tokenValue, long tokenId) throws ContractValidateException {
    if (VMConfig.allowTvmTransferTrc10() && VMConfig.allowMultiSign()) {
      // tokenid can only be 0
      // or (MIN_TOKEN_ID, Long.Max]
      if (tokenId <= VMConstant.MIN_TOKEN_ID && tokenId != 0) {
        throw new ContractValidateException("tokenId must be > " + VMConstant.MIN_TOKEN_ID);
      }
      // tokenid can only be 0 when tokenvalue = 0,
      // or (MIN_TOKEN_ID, Long.Max]
      if (tokenValue > 0 && tokenId == 0) {
        throw new ContractValidateException("invalid arguments with tokenValue = "
            + tokenValue + ", tokenId = " + tokenId);
      }
    }
  }


  private double getCpuLimitInUsRatio() {
    return CommonParameter.getInstance().getMaxTimeRatio();
  }

  private boolean isCheckTransaction() {
    return this.blockCap != null && !this.blockCap.getInstance().getBlockHeader()
        .getWitnessSignature().isEmpty();
  }

}
